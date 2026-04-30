(ns wun.web.core
  "Wun web entry point. Subscribes to the server's SSE patch stream,
   delegates each envelope to `wun.web.intent-bus`, and renders
   `display-tree` through reagent. The optimistic dispatch + reconcile
   machinery lives in `intent-bus`. Side-effecting requires below
   populate the open registries on load."
  (:require [cognitect.transit  :as transit]
            [reagent.dom.client :as rdc]
            [wun.capabilities   :as capabilities]
            [wun.components     :as components]
            [wun.web.intent-bus :as bus]
            [wun.web.renderers  :as renderers]
            ;; populate registries:
            wun.foundation.components
            wun.web.foundation
            wun.app.counter
            wun.app.about))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; ---------------------------------------------------------------------------
;; Transit reader

(def ^:private reader (transit/reader :json))

(defn- str->t [s] (transit/read reader s))

;; ---------------------------------------------------------------------------
;; Status indicator (outside the reagent root, so we don't fight React over
;; the #status div)

(defn- ^js status-el [] (.getElementById js/document "status"))

(defn- set-status! [s]
  (when-let [el (status-el)] (set! (.-textContent el) s)))

(defn- set-offline! [offline?]
  ;; LiveView ships a `phx-disconnected` body class so apps can dim
  ;; their UI during a reconnect. Same idea here -- the live UI is
  ;; still on screen (we don't blow away `display-tree`), but it's
  ;; visually attenuated so the user knows clicks won't take
  ;; immediate effect.
  (let [body (.-body js/document)
        cl   (.-classList body)]
    (if offline?
      (.add cl "wun-offline")
      (.remove cl "wun-offline"))))

;; ---------------------------------------------------------------------------
;; Top-level reagent component. Derefs display-tree (a reagent atom)
;; so reagent re-renders on every change.

(defn app []
  (renderers/render-node @bus/display-tree))

;; ---------------------------------------------------------------------------
;; SSE wiring

(defonce ^:private es (atom nil))
(defonce ^:private was-connected? (atom false))

(defn- current-caps
  "Build the capability map from registered web renderers; the version
   for each comes from the shared component registry's :since."
  []
  (->> (renderers/registered)
       (map (fn [k] [k (or (:since (components/lookup k)) 1)]))
       (into {})))

(defn- caps-url []
  (let [path (or (some-> js/window .-location .-pathname) "/")]
    (str server-base
         "/wun?caps=" (js/encodeURIComponent
                       (capabilities/serialize (current-caps)))
         "&path="    (js/encodeURIComponent path))))

(defn- start-sse! []
  (when-let [old @es] (.close old))
  (let [src (js/EventSource. (caps-url))]
    (.addEventListener src "patch"
      (fn [ev]
        (when-not @was-connected?
          (js/console.info "wun: SSE connected"))
        (reset! was-connected? true)
        (set-status! "connected")
        (set-offline! false)
        (bus/apply-envelope! (str->t (.-data ev)))))
    (.addEventListener src "open"
      (fn [_]
        (when-not @was-connected?
          (js/console.info "wun: SSE open"))
        (set-status! "connected")
        (set-offline! false)))
    (.addEventListener src "error"
      (fn [_]
        (when @was-connected?
          (js/console.warn "wun: SSE disconnected; EventSource will retry"))
        (reset! was-connected? false)
        (set-status! "disconnected (browser will retry)")
        (set-offline! true)))
    (reset! es src)))

;; ---------------------------------------------------------------------------
;; Reagent mount

(defonce ^:private root (atom nil))

(defn- mount! []
  (when-not @root
    (reset! root (rdc/create-root (.getElementById js/document "app"))))
  (rdc/render @root [app]))

;; ---------------------------------------------------------------------------
;; Entry points

(defn ^:export init []
  (mount!)
  ;; Hydrate from localStorage BEFORE opening the stream so the user
  ;; sees last-known UI immediately on reload (Hotwire-snapshot-style)
  ;; rather than a blank screen waiting for the first SSE frame.
  (bus/hydrate-from-cache!)
  (bus/start-pending-gc!)
  (bus/install-popstate-handler!)
  (start-sse!))

(defn ^:export after-reload []
  (mount!))
