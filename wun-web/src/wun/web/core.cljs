(ns wun.web.core
  "Wun web entry point. Subscribes to the server's SSE patch stream,
   delegates each envelope to `wun.web.intent-bus`, and renders the
   `display-tree` atom through Replicant. The optimistic dispatch +
   reconcile machinery lives in `intent-bus`. Side-effecting requires
   below populate the open registries on load.

   Phase 3 swap: was Reagent + React-DOM. Now it's Replicant -- a
   pure-Clojure VDOM with zero JS dependencies. The hiccup the
   renderers produce is unchanged; what changed is the rendering
   substrate (replicant.dom/render!) and the reactivity model (an
   atom watcher pushes a new tree into Replicant on every change to
   `display-tree` rather than relying on reagent's auto-deref-tracking
   reactive atoms)."
  (:require [cognitect.transit  :as transit]
            [replicant.dom      :as r]
            [wun.capabilities   :as capabilities]
            [wun.components     :as components]
            [wun.web.intent-bus :as bus]
            [wun.web.persist    :as persist]
            [wun.web.renderers  :as renderers]
            ;; populate registries:
            wun.foundation.components
            wun.foundation.theme
            wun.forms.intents
            wun.web.foundation
            wun.app.counter
            wun.app.about))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; Wire envelope versions this client knows how to render.
(def supported-envelope-versions [1 2])
(def preferred-envelope-version 2)

;; ---------------------------------------------------------------------------
;; Transit reader

(def ^:private reader (transit/reader :json))

(defn- str->t [s] (transit/read reader s))

;; ---------------------------------------------------------------------------
;; Status indicator (outside the Replicant root, so we don't fight it
;; over ownership of the #status div)

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
;; Replicant render. We render the current `display-tree` directly --
;; renderers/render-node converts Wun Hiccup (`:wun/Stack`, ...) into
;; plain HTML Hiccup that Replicant's renderer understands.

(defonce ^:private root-el (atom nil))

(defn- render! []
  (when-let [el @root-el]
    (let [tree (or @bus/display-tree
                   ;; Cold-start placeholder while we wait for the SSE
                   ;; stream to deliver the first frame.
                   [:div.wun-bootstrapping {} ""])]
      (r/render! el (renderers/render-node tree)))))

;; The bus' display-tree atom drives all renders. We add a single
;; watcher; recompute! pushes a new value, the watcher fires, and
;; Replicant diffs against its prior internal state to emit minimal
;; DOM ops. No reactive deref tree like reagent's r/atom, no
;; unnecessary subscriptions.
(defonce ^:private display-tree-watch
  (delay
    (add-watch bus/display-tree ::render
               (fn [_ _ _ _] (render!)))))

;; ---------------------------------------------------------------------------
;; SSE wiring

(defonce ^:private es (atom nil))
(defonce ^:private was-connected? (atom false))
;; Sticky: stays true after the first successful connect for the
;; lifetime of this JS context. Used to distinguish a brand-new
;; connection from a reconnect-after-drop, since EventSource resets
;; was-connected? to false on every error.
(defonce ^:private ever-connected? (atom false))

;; Heartbeat watchdog: server emits `:ping` envelopes on a separate
;; SSE event name; we track the last-received timestamp and force a
;; manual reconnect if it goes stale (proxies that silently kill the
;; connection without surfacing the close to EventSource).
(defonce ^:private last-frame-ms (atom nil))
(def ^:private heartbeat-watchdog-ms 60000)

(defn- current-caps
  "Build the capability map from registered web renderers; the version
   for each comes from the shared component registry's :since."
  []
  (->> (renderers/registered)
       (map (fn [k] [k (or (:since (components/lookup k)) 1)]))
       (into {})))

(defn- persisted-session-token
  "Pull a server-issued session token out of localStorage if a previous
   login dropped one there. The token rides on `?session-token=` so the
   server's init-state-fn can rehydrate the user's saved slice during
   the SSE handshake. EventSource can't set custom headers, so query
   param is the only option for the web client; native clients use
   the `X-Wun-Session` request header instead."
  []
  (some-> (persist/load) :confirmed-state :session :token))

(defn- caps-url []
  (let [path  (or (some-> js/window .-location .-pathname) "/")
        token (persisted-session-token)]
    (str server-base
         "/wun?caps="     (js/encodeURIComponent
                           (capabilities/serialize (current-caps)))
         "&path="         (js/encodeURIComponent path)
         "&envelope="     preferred-envelope-version
         (when token (str "&session-token=" (js/encodeURIComponent token))))))

(declare start-sse!)

(defn- handle-patch-event! [data]
  (reset! last-frame-ms (.getTime (js/Date.)))
  (when-not @was-connected?
    (js/console.info "wun: SSE connected"))
  (reset! was-connected? true)
  (reset! ever-connected? true)
  (set-status! "connected")
  (set-offline! false)
  (bus/apply-envelope! (str->t data)))

(defn- handle-heartbeat-event! [_]
  ;; Heartbeat envelopes are JSON-encoded `{:type :ping :ts ms}` --
  ;; we just need the side effect of resetting the watchdog timer.
  ;; We don't actually need to parse them; the SSE event firing
  ;; means we're alive.
  (reset! last-frame-ms (.getTime (js/Date.))))

(defn- watchdog-tick! []
  (when-let [t @last-frame-ms]
    (let [age (- (.getTime (js/Date.)) t)]
      (when (> age heartbeat-watchdog-ms)
        (js/console.warn "wun: no frame in" age "ms; forcing reconnect")
        (reset! last-frame-ms nil)
        (start-sse!)))))

(defonce ^:private watchdog-handle (atom nil))

(defn- start-watchdog! []
  (when-let [h @watchdog-handle] (js/clearInterval h))
  (reset! watchdog-handle
          (js/setInterval watchdog-tick! 5000)))

(defn- start-sse! []
  (when-let [old @es] (.close old))
  (let [src (js/EventSource. (caps-url))]
    (.addEventListener src "patch"
      (fn [ev] (handle-patch-event! (.-data ev))))
    (.addEventListener src "heartbeat"
      (fn [ev] (handle-heartbeat-event! (.-data ev))))
    (.addEventListener src "open"
      (fn [_]
        (let [reconnect? @ever-connected?]
          (when-not reconnect?
            (js/console.info "wun: SSE open"))
          (set-status! "connected")
          (set-offline! false)
          (reset! last-frame-ms (.getTime (js/Date.)))
          ;; On reconnect, replay any still-pending intents the server
          ;; may have missed during the outage. Server-side dedup
          ;; (keyed by intent id) makes this safe.
          (when reconnect? (bus/replay-pending!)))))
    (.addEventListener src "error"
      (fn [_]
        (when @was-connected?
          (js/console.warn "wun: SSE disconnected; EventSource will retry"))
        (reset! was-connected? false)
        (set-status! "disconnected (browser will retry)")
        (set-offline! true)))
    (reset! es src)))

;; ---------------------------------------------------------------------------
;; Replicant mount

(defn- mount! []
  (when-not @root-el
    (reset! root-el (.getElementById js/document "app")))
  ;; Force the watcher delay so it actually wires the atom -> DOM hook.
  @display-tree-watch
  (render!))

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
  (start-watchdog!)
  (start-sse!))

(defn ^:export after-reload []
  (mount!))
