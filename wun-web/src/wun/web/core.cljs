(ns wun.web.core
  "Wun web entry point. Subscribes to the server's SSE patch stream,
   delegates each envelope to `wun.web.intent-bus`, and mounts whatever
   the bus produces as `display-tree` to the DOM.

   The optimistic dispatch + reconcile machinery lives in `intent-bus`
   so renderers (which fire intents from button click handlers) and
   this file (which feeds incoming envelopes) share one source of
   truth. Side-effecting requires below populate the open registries."
  (:require [cognitect.transit  :as transit]
            [wun.web.intent-bus :as bus]
            [wun.web.renderers  :as renderers]
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
;; Mount

(defn- ^js status-el [] (.getElementById js/document "status"))
(defn- ^js app-el    [] (.getElementById js/document "app"))

(defn- set-status! [s]
  (when-let [el (status-el)] (set! (.-textContent el) s)))

(add-watch bus/display-tree ::mount
  (fn [_ _ _ tree]
    (when-let [root (app-el)]
      (renderers/mount-tree! root tree))))

;; ---------------------------------------------------------------------------
;; SSE wiring

(defonce ^:private es (atom nil))

(defn- start-sse! []
  (when-let [old @es] (.close old))
  (let [src (js/EventSource. (str server-base "/wun"))]
    (.addEventListener src "patch"
      (fn [ev]
        (set-status! "connected")
        (bus/apply-envelope! (str->t (.-data ev)))))
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
    (renderers/mount-tree! root @bus/display-tree)))
