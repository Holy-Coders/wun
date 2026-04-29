(ns wun.web.core
  "Wun web entry point. Connects to the server's SSE patch stream,
   applies patches into a local tree mirror, and re-renders via the
   open renderer registry on every update.

   Renderers and the foundational `:wun/*` web bindings live in
   sibling namespaces; this file only wires SSE + state + mount.
   Side-effecting requires populate the registries on load."
  (:require [cognitect.transit :as transit]
            [wun.web.renderers :as renderers]
            ;; populate registries:
            wun.foundation.components
            wun.web.foundation
            wun.app.counter))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; ---------------------------------------------------------------------------
;; Transit reader

(def ^:private reader (transit/reader :json))

(defn- str->t [s] (transit/read reader s))

;; ---------------------------------------------------------------------------
;; Local tree mirror

(defonce tree-state (atom nil))

(defn- ^js status-el [] (.getElementById js/document "status"))
(defn- ^js app-el    [] (.getElementById js/document "app"))

(defn- set-status! [s]
  (when-let [el (status-el)] (set! (.-textContent el) s)))

;; Re-mount whenever the mirror changes.
(add-watch tree-state ::mount
  (fn [_ _ _ tree]
    (when-let [root (app-el)]
      (renderers/mount-tree! root tree))))

;; ---------------------------------------------------------------------------
;; Patch application -- phase 0/1.A only honours :replace at root.
;; Phase 1.B introduces path-aware :replace / :insert / :remove.

(defn- apply-patch! [{:keys [op path value]}]
  (case op
    :replace (if (empty? path)
               (reset! tree-state value)
               (js/console.warn "wun: non-root :replace not implemented" (clj->js path)))
    (js/console.warn "wun: unsupported op" (str op))))

(defn- apply-envelope! [{:keys [patches status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (doseq [p patches] (apply-patch! p)))

;; ---------------------------------------------------------------------------
;; SSE wiring

(defonce ^:private es (atom nil))

(defn- start-sse! []
  (when-let [old @es] (.close old))
  (let [src (js/EventSource. (str server-base "/wun"))]
    (.addEventListener src "patch"
      (fn [ev]
        (set-status! "connected")
        (apply-envelope! (str->t (.-data ev)))))
    (.addEventListener src "open"
      (fn [_] (set-status! "connected")))
    (.addEventListener src "error"
      (fn [_] (set-status! "disconnected (browser will retry)")))
    (reset! es src)))

;; ---------------------------------------------------------------------------
;; Entry points

(defn ^:export init []
  (start-sse!))

(defn ^:export after-reload []
  (when-let [root (app-el)]
    (renderers/mount-tree! root @tree-state)))
