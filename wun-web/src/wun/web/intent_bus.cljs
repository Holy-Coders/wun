(ns wun.web.intent-bus
  "Optimistic intent dispatcher and reconciler. The brief's marquee
   thesis: one morph fn defined in shared .cljc, run on both server
   (authoritative) and client (optimistic prediction).

   State on the client:

     confirmed-state  -- screen state mirrored from the server's
                         envelopes. Updated on every server frame.
     confirmed-tree   -- tree the server's per-connection prior tracks.
                         Updated by applying envelope patches.
     pending          -- ordered vec of {:id :intent :params :created-at}
                         the user has fired but the server hasn't
                         confirmed yet. Tagged with a wall-clock
                         timestamp so stale entries can be GC'd.
     display-tree     -- what the DOM is rendered against. Always
                         equals (render-screen pred-state) where
                         pred-state = (reduce morph confirmed-state
                                       pending).

   Phase 3 swap: was reagent atoms + reagent's r/atom/deref reactivity.
   Now it's plain ClojureScript atoms with an explicit `notify-render!`
   hook the core ns wires up to Replicant. The benefit: no React, no
   reagent runtime, smaller bundle. The cost: we re-render on demand
   instead of on auto-subscription.

   Dispatch path:
     1. Append {:id :intent :params :created-at} to pending.
     2. Recompute display-tree (predicted state -> render).
     3. POST the intent envelope; the server will broadcast a patch
        tagged with :resolves-intent equal to this id.

   Reconcile path (per server envelope):
     1. If the envelope contains a :replace at root path, treat it
        as a fresh server view (initial connect or reconnect after
        disconnect) and clear pending. The new tree already reflects
        whatever the server processed; pending entries are stale.
     2. Apply patches to confirmed-tree.
     3. Replace confirmed-state with the envelope's :state when
        present.
     4. Drop the entry whose :id matches :resolves-intent from pending.
     5. Recompute display-tree. If our prediction matched, no visible
        change. If it didn't, the tree converges to the new
        authoritative prediction (the brief's match / refine /
        conflict semantics).

   Pending TTL: a periodic GC drops pending entries older than
   pending-ttl-ms. POST failures that the client didn't catch (rare
   with fetch's promise rejection, but possible) would otherwise
   leave a permanent ghost in pending; the TTL bounds the damage."
  (:require [cognitect.transit  :as transit]
            [goog.object        :as gobject]
            [wun.capabilities   :as capabilities]
            [wun.components     :as components]
            [wun.diff           :as diff]
            [wun.intents        :as intents]
            [wun.screens        :as screens]
            [wun.web.meta       :as wmeta]
            [wun.web.persist    :as persist]
            [wun.web.renderers  :as renderers]))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; Pending entries older than this get evicted on the GC tick.
(def ^:private pending-ttl-ms 30000)

;; How often the GC tick runs.
(def ^:private gc-interval-ms 5000)

;; ---------------------------------------------------------------------------
;; State

(defonce confirmed-state (atom nil))
(defonce confirmed-tree  (atom nil))
(defonce pending         (atom []))
;; display-tree is a plain atom; the core ns adds a watcher that calls
;; Replicant's render! whenever it changes. There is intentionally no
;; reactive deref happening anywhere -- explicit `recompute!` is the
;; single edge that pushes a new tree into Replicant.
(defonce display-tree    (atom nil))

;; Server-assigned connection id. The first SSE envelope echoes one;
;; subsequent /intent POSTs include it so framework intents
;; (`:wun/navigate`, `:wun/pop`) can be routed to *this* connection's
;; screen-stack rather than to all connections.
(defonce conn-id         (atom nil))

;; CSRF token bound to this conn / session, issued on the first SSE
;; envelope. Echoed on /intent POSTs as the `X-Wun-CSRF` header so
;; the server's CSRF interceptor accepts the request.
(defonce csrf-token      (atom nil))

;; The connection's current screen-stack (top is the visible screen).
;; The server is the source of truth: every envelope that mutates the
;; stack carries the new value, and recompute! always renders the top.
;; The reducer guards against the very first envelope showing up
;; before the connect frame -- it falls back to the path lookup of
;; the current URL and finally `:counter/main`.
(defonce screen-stack
  (atom
   [(or (some-> js/window .-location .-pathname screens/lookup-by-path)
        :counter/main)]))

;; Presentation hint (`:push` / `:modal`) for each entry in the
;; screen-stack. Web treats both as a regular page swap today, but
;; native clients use it to decide sheet-vs-push. We mirror it here
;; so the wire stays uniform across platforms.
(defonce presentations (atom [:push]))

(defn current-screen-key [] (peek @screen-stack))

(defn- now-ms [] (.getTime (js/Date.)))

(defn- predicted-state []
  (reduce (fn [s {:keys [intent params]}]
            (if (intents/server-only? intent)
              s
              (intents/apply-intent s intent params)))
          @confirmed-state
          @pending))

(defn- client-caps
  "The same caps map the client advertises on the SSE URL. Built
   from the web renderer registry; versions read from the shared
   component registry's `:since` (defaults to 1)."
  []
  (into {} (for [k (renderers/registered)]
             [k (or (:since (components/lookup k)) 1)])))

(defn recompute!
  "Re-derive display-tree by:
     1. running the *current* screen's render fn (top of the
        screen-stack) against the predicted state
     2. applying capability substitution against the client's own
        cap profile -- otherwise the locally-rendered tree (used for
        optimistic UI) bypasses the substitution that only the
        server's broadcast goes through, and components like
        :myapp/Greeting that the client can't render show up as
        literal :myapp/Greeting elements (and fall through to the
        unknown-renderer placeholder) instead of as :wun/WebFrame
        fallbacks."
  []
  (let [tree (screens/render (current-screen-key) (predicted-state))]
    (reset! display-tree (capabilities/substitute tree (client-caps)))))

;; ---------------------------------------------------------------------------
;; Transit + POST

(def ^:private writer (transit/writer :json))

(defn- t->str [v] (transit/write writer v))

(defn- build-headers []
  (let [h #js {"Content-Type" "application/transit+json"}]
    (when-let [tok @csrf-token]
      (gobject/set h "X-Wun-CSRF" tok))
    h))

(defn- post-intent! [intent params id]
  (-> (js/fetch (str server-base "/intent")
                #js {:method  "POST"
                     :headers (build-headers)
                     :body    (t->str (cond-> {:intent intent
                                               :params (or params {})
                                               :id     id}
                                        @conn-id    (assoc :conn-id @conn-id)
                                        @csrf-token (assoc :csrf-token @csrf-token)))})
      (.catch (fn [e] (js/console.error "wun: intent failed" e)))))

;; ---------------------------------------------------------------------------
;; Public dispatch + reconcile

;; Framework intents are routed by the server itself (per-connection
;; navigation), not by the registered morph dispatcher. They skip
;; client-side param validation -- the schema lives server-side -- and
;; produce no optimistic state change since they reshape *which screen
;; is rendered*, not the screen's own data. They still go through the
;; pending tracker so the resolves-intent round-trip works the same.
(def ^:private framework-intents
  #{:wun/navigate :wun/pop :wun/replace})

(defn dispatch!
  "Optimistically fire `intent` with `params`. Validates params against
   the intent's :params schema first (skipped for framework intents);
   on failure, logs a warning and drops the intent without optimistic
   prediction or POST. On success, appends a pending entry, recomputes
   the predicted display, and POSTs the envelope to the server.
   Returns the intent id, or nil when validation rejected the call."
  [intent params]
  (let [params (or params {})]
    (if-let [err (when-not (contains? framework-intents intent)
                   (intents/validate-params intent params))]
      (do (js/console.warn "wun: invalid intent params" (clj->js err))
          nil)
      (let [id (random-uuid)]
        (swap! pending conj {:id         id
                             :intent     intent
                             :params     params
                             :created-at (now-ms)})
        (recompute!)
        (post-intent! intent params id)
        id))))

;; Convenience wrappers so user-facing components don't have to know
;; the framework-intent vocabulary verbatim.

(defn navigate!
  "Push a new screen onto the connection's stack. `target` may be a
   keyword like `:about/main` (matched against the screen registry)
   or a path string like `\"/about\"` (matched against `:path`)."
  [target]
  (dispatch! :wun/navigate
             (if (keyword? target)
               {:screen target}
               {:path target})))

(defn pop-screen!
  "Pop the top of the connection's screen-stack. Renamed to avoid
   shadowing `cljs.core/pop!`."
  []
  (dispatch! :wun/pop {}))

;; ---------------------------------------------------------------------------
;; File uploads. POSTs raw file bytes to /upload with metadata in
;; headers; the server pushes progress patches via the SSE stream.
;; The `:uploads` map under app state is the source of truth -- the
;; UI binds against it, not against the local upload-id we generate.

(defn- random-uuid-str []
  (.toString (random-uuid)))

(defn start-upload!
  "Begin uploading a browser File handle bound to `form` / `field`.
   Returns the upload-id; the server will populate the matching
   `:uploads <id>` entry as bytes flow."
  [form field ^js file]
  (let [uid (random-uuid-str)
        headers (cond-> #js {"X-Wun-Conn-ID"   (or @conn-id "")
                             "X-Wun-Upload-ID" uid
                             "X-Wun-Filename"  (.-name file)
                             "X-Wun-Size"      (str (.-size file))
                             "Content-Type"    (or (.-type file)
                                                   "application/octet-stream")}
                  form  (gobject/set "X-Wun-Form"  (clojure.core/name form))
                  field (gobject/set "X-Wun-Field" (clojure.core/name field))
                  @csrf-token (gobject/set "X-Wun-CSRF" @csrf-token))]
    (-> (js/fetch (str server-base "/upload")
                  #js {:method  "POST"
                       :headers headers
                       :body    file})
        (.catch (fn [e] (js/console.error "wun: upload failed" e))))
    uid))

(defn replay-pending!
  "Re-POST every still-pending intent. Called when SSE reconnects so
   anything fired during the offline window actually reaches the
   server. The server's idempotency cache (keyed by `:id`) absorbs
   duplicates -- intents already processed return their cached
   response without re-running the morph."
  []
  (let [now    (now-ms)
        cutoff (- now pending-ttl-ms)
        live   (vec (remove #(< (:created-at % 0) cutoff) @pending))]
    (when-not (= live @pending)
      (reset! pending live))
    (when (seq live)
      (js/console.info "wun: replaying" (count live) "pending intent(s)")
      (doseq [{:keys [intent params id]} live]
        (post-intent! intent params id)))))

(defn- bootstrap-frame?
  "True when an envelope's patches contain a :replace at root, which
   the server emits on (re)connect or screen-level restructure. We
   treat this as a fresh server view and drop pending."
  [patches]
  (some (fn [{:keys [op path]}]
          (and (= op :replace) (= [] path)))
        patches))

(defn- sync-url!
  "Sync the browser URL with the top screen's `:path`, so reload
   restores the same screen and the back / forward buttons feel
   native. Skip when the path already matches (avoids spurious
   history entries)."
  [screen-key]
  (when-let [path (some-> screen-key screens/lookup :path)]
    (let [loc js/window.location
          cur (str (.-pathname loc) (.-search loc) (.-hash loc))]
      (when-not (= cur path)
        (.pushState (.-history js/window) #js {} "" path)))))

;; Last meta the server sent us, kept locally so we can persist it
;; with the rest of the snapshot.
(defonce last-meta (atom nil))

(defn- persist-snapshot! []
  (persist/save! {:confirmed-state @confirmed-state
                  :confirmed-tree  @confirmed-tree
                  :screen-stack    @screen-stack
                  :meta            @last-meta}))

(defn apply-envelope!
  "Reconcile a server envelope: maybe-bootstrap, apply patches against
   confirmed-tree, mirror confirmed-state, drop the resolved pending
   entry, optionally update the conn-id / csrf-token / screen-stack the
   server sent for this connection, apply page meta (title /
   description / theme-color) to the document head, sync the browser
   URL to the visible screen, persist the snapshot to localStorage,
   and recompute the display.

   Heartbeat envelopes (`{:type :ping}`) are routed elsewhere; this fn
   only handles patch envelopes."
  [{cid    :conn-id
    csrf   :csrf-token
    stack  :screen-stack
    pres   :presentations
    meta   :meta
    env-v  :envelope-version
    rsync? :resync?
    :keys [patches state resolves-intent status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (when rsync?
    (js/console.info "wun: server forced snapshot resync (backpressure)")
    (reset! pending []))
  ;; (Note: we no longer clear `pending` on bootstrap. Server-side
  ;; intent dedup makes retrying safe -- the next POSTs will either
  ;; be no-ops (because the server already processed them) or run
  ;; fresh (because the disconnect happened before the server saw
  ;; them). The client-side TTL still drops stale entries.)
  (when (and (seq patches) (bootstrap-frame? patches) (seq @pending))
    (js/console.info "wun: bootstrap frame; keeping" (count @pending)
                     "pending intent(s) for retry"))
  (when cid (reset! conn-id cid))
  (when csrf (reset! csrf-token csrf))
  (when (and env-v (number? env-v) (> env-v 2))
    (js/console.warn "wun: server envelope-version" env-v
                     "newer than client supports; rendering may degrade"))
  (when (seq stack)
    (let [normalized (mapv #(if (string? %) (keyword %) %) stack)]
      (reset! screen-stack normalized)
      (sync-url! (peek normalized))))
  (when (seq pres)
    (reset! presentations
            (mapv #(if (string? %) (keyword %) %) pres)))
  (when (seq patches)
    (swap! confirmed-tree diff/apply-patches patches))
  (when (some? state)
    (reset! confirmed-state state))
  (when meta
    (reset! last-meta meta)
    (wmeta/apply-meta! meta))
  (when resolves-intent
    (swap! pending (fn [ps] (vec (remove #(= (:id %) resolves-intent) ps)))))
  (recompute!)
  (persist-snapshot!))

(defn hydrate-from-cache!
  "On cold start, populate the local atoms from the last snapshot
   localStorage holds for this path. Idempotent. Returns true iff we
   found a snapshot -- callers can use that to decide whether to
   render before the SSE stream connects."
  []
  (let [snap (persist/load)
        {snap-state :confirmed-state
         snap-tree  :confirmed-tree
         snap-stack :screen-stack
         snap-meta  :meta} snap]
    (when snap-state (reset! confirmed-state snap-state))
    (when snap-tree  (reset! confirmed-tree  snap-tree))
    (when (seq snap-stack)
      (let [normalized (mapv #(if (string? %) (keyword %) %) snap-stack)]
        (reset! screen-stack normalized)))
    (when snap-meta
      (reset! last-meta snap-meta)
      (wmeta/apply-meta! snap-meta))
    (recompute!)
    (some? snap)))

;; Wire the browser's popstate to a server-side pop so the back button
;; behaves like the in-app back button. We only call `pop!` if there's
;; somewhere to pop to; otherwise the navigation happens naturally
;; (re-loading the document at the new URL on a page reload).
(defn install-popstate-handler!
  []
  (.addEventListener js/window "popstate"
                     (fn [_]
                       (when (> (count @screen-stack) 1)
                         (pop-screen!)))))

;; ---------------------------------------------------------------------------
;; Pending GC

(defn- gc-pending! []
  (let [cutoff (- (now-ms) pending-ttl-ms)
        before (count @pending)
        live   (vec (remove #(< (:created-at % 0) cutoff) @pending))]
    (when (< (count live) before)
      (js/console.warn "wun: dropping" (- before (count live))
                       "stale pending intent(s) older than"
                       pending-ttl-ms "ms")
      (reset! pending live)
      (recompute!))))

(defonce ^:private gc-handle (atom nil))

(defn start-pending-gc! []
  (when-let [h @gc-handle] (js/clearInterval h))
  (reset! gc-handle (js/setInterval gc-pending! gc-interval-ms)))
