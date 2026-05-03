(ns wun.server.state
  (:require [clojure.core.async.impl.protocols :as async-impl]))

;; ---------------------------------------------------------------------------
;; Per-connection app state (phase 1.E: was a single global atom in earlier
;; phases). Each SSE connection has its own state slice keyed by `conn-id`
;; (a UUID issued by `add-connection!` and echoed by the client on every
;; /intent POST). Intent morphs run against the originating slice and
;; broadcasts go only to that connection -- there is *no* fan-out across
;; conns. Genuinely shared state (a chat room, a leaderboard) is built by
;; an app-level intent that walks `conn-states` itself; the framework
;; deliberately doesn't have a "broadcast to everyone" affordance because
;; most state is per-user, and the cross-conn case is the unusual one.
;;
;; New connections start with `*init-state*` -- override per-app via
;; `(alter-var-root #'wun.server.state/*init-state* (constantly {...}))`
;; in your server `-main`, or via `register-init-state-fn!` (see below)
;; for state that depends on hydration from a DB.

(def ^:dynamic *init-state* {})

;; A function (or nil) called as `(f conn-id)` when a fresh slice is
;; created; returns the initial state map. Apps that hydrate from a
;; durable store (the optional `myapp.server.persist` fragment) install
;; an init-state-fn that consults the DB before falling back to
;; *init-state*. Kept as a single fn rather than a registry because
;; init order matters and we want one explicit owner.
(defonce init-state-fn (atom nil))

(defn register-init-state-fn! [f]
  (reset! init-state-fn f))

(defn- compute-init-state [conn-id]
  (if-let [f @init-state-fn]
    (or (f conn-id) *init-state*)
    *init-state*))

(defonce conn-states (atom {}))

(defn state-for
  "Return the state map for `conn-id`, or `*init-state*` if the slice
   doesn't exist yet."
  [conn-id]
  (get @conn-states conn-id *init-state*))

(defn ensure-state-for!
  "Make sure a slice exists for `conn-id`, calling the init-state-fn if
   needed. Idempotent: re-runs return the existing slice unchanged."
  [conn-id]
  (-> (swap! conn-states
             (fn [m]
               (if (contains? m conn-id)
                 m
                 (assoc m conn-id (compute-init-state conn-id)))))
      (get conn-id)))

(defn swap-state-for!
  "Apply `(f current-state args...)` to the slice for `conn-id` and
   return the new state. Creates the slice with the init-state-fn if
   it doesn't exist."
  [conn-id f & args]
  (let [m (swap! conn-states
                 (fn [m]
                   (let [s   (if (contains? m conn-id)
                               (get m conn-id)
                               (compute-init-state conn-id))
                         s'  (apply f s args)]
                     (assoc m conn-id s'))))]
    (get m conn-id)))

(defn drop-state-for! [conn-id]
  (swap! conn-states dissoc conn-id))

;; ---------------------------------------------------------------------------
;; Watch hook: a fn (or nil) called as `(f conn-id new-state old-state)`
;; whenever a slice mutates. Set by the optional `myapp.server.persist`
;; fragment to debounce-write the slice to a durable store. Single fn,
;; not a registry, because order matters and we want one explicit owner.

(defonce ^:private state-watch-fn (atom nil))

(defn register-state-watch! [f]
  (reset! state-watch-fn f))

(add-watch conn-states ::user-watch
           (fn [_ _ old new]
             (when-let [f @state-watch-fn]
               (doseq [cid (set (concat (keys old) (keys new)))]
                 (let [o (get old cid) n (get new cid)]
                   (when (not= o n) (f cid n o)))))))

;; ---------------------------------------------------------------------------
;; SSE connections are keyed by core.async channel and carry per-conn
;; metadata: the *prior tree* the connection has seen (for diffing),
;; the *caps* the client advertised (for capability substitution),
;; the wire *fmt* (:transit or :json), the *screen-stack* (top is the
;; currently-rendered screen), and a *conn-id* the client echoes on
;; /intent POSTs so the server can route framework intents
;; (`:wun/navigate`, `:wun/pop`) back to the originating connection.
;;
;; New connections register with prior=nil; diff(nil, current) emits
;; a full :replace-at-root, so the initial frame falls out of the
;; same code path as ongoing broadcasts.
(defonce connections (atom {}))

(defn add-connection! [event-ch caps fmt screen-key conn-id]
  (swap! connections assoc event-ch {:prior        nil
                                     :caps         caps
                                     :fmt          fmt
                                     :screen-stack [screen-key]
                                     :conn-id      conn-id}))

(defn remove-connection! [event-ch]
  (swap! connections dissoc event-ch))

(defn prior-tree [event-ch]
  (get-in @connections [event-ch :prior]))

(defn caps [event-ch]
  (get-in @connections [event-ch :caps]))

(defn fmt [event-ch]
  (get-in @connections [event-ch :fmt]))

(defn screen-stack [event-ch]
  (get-in @connections [event-ch :screen-stack]))

(defn screen-key
  "The screen currently rendered on `event-ch` -- the top of its
   per-connection screen-stack."
  [event-ch]
  (peek (get-in @connections [event-ch :screen-stack])))

(defn conn-id [event-ch]
  (get-in @connections [event-ch :conn-id]))

(defn channel-by-conn-id
  "Look up the SSE event channel registered under `conn-id`. Returns
   nil when no connection matches (likely closed since the client last
   received the id)."
  [conn-id]
  (some (fn [[ch v]]
          (when (= conn-id (:conn-id v)) ch))
        @connections))

(defn push-screen!
  "Push `screen-key` onto the connection's screen-stack. Returns the
   updated stack, or nil when the connection isn't registered (closed
   between the intent posting and the routing call)."
  [event-ch screen-key]
  (let [m (swap! connections
                 (fn [m]
                   (if (contains? m event-ch)
                     (update-in m [event-ch :screen-stack] (fnil conj []) screen-key)
                     m)))]
    (get-in m [event-ch :screen-stack])))

(defn pop-screen!
  "Pop the top of the connection's screen-stack, leaving at least one
   screen behind (the root). Returns the updated stack, or nil when
   the connection isn't registered."
  [event-ch]
  (let [m (swap! connections
                 (fn [m]
                   (if (contains? m event-ch)
                     (update-in m [event-ch :screen-stack]
                                (fn [stack]
                                  (if (> (count stack) 1)
                                    (vec (butlast stack))
                                    stack)))
                     m)))]
    (get-in m [event-ch :screen-stack])))

(defn replace-screen!
  "Replace the top of the screen-stack with `screen-key`. Useful for
   `:wun/replace`-style navigation (think 'redirect') that doesn't
   want to grow the stack."
  [event-ch screen-key]
  (let [m (swap! connections
                 (fn [m]
                   (if (contains? m event-ch)
                     (update-in m [event-ch :screen-stack]
                                (fn [stack]
                                  (if (seq stack)
                                    (assoc stack (dec (count stack)) screen-key)
                                    [screen-key])))
                     m)))]
    (get-in m [event-ch :screen-stack])))

(defn update-prior-tree! [event-ch tree]
  (swap! connections (fn [m]
                       (if (contains? m event-ch)
                         (assoc-in m [event-ch :prior] tree)
                         m))))

;; Per-connection meta cache. Mirrors :prior for the head-side of
;; the wire: store the last meta map we sent so a no-op recompute
;; doesn't ride every envelope. Comparing by value (=) is fine since
;; meta maps are tiny.

(defn prior-meta [event-ch]
  (get-in @connections [event-ch :prior-meta]))

(defn update-prior-meta! [event-ch meta]
  (swap! connections (fn [m]
                       (if (contains? m event-ch)
                         (assoc-in m [event-ch :prior-meta] meta)
                         m))))

(defn closed?
  "True when `event-ch` has been closed (typically by Pedestal after
   the client disconnects). Reaches into core.async's impl protocol;
   there's no public predicate for this in 1.6.x."
  [event-ch]
  (async-impl/closed? event-ch))

(defn evict-closed!
  "Remove any connections whose channels have been closed. Returns
   the number evicted."
  []
  (let [old @connections
        live (into {} (remove (fn [[ch _]] (closed? ch)) old))]
    (when (not= (count old) (count live))
      (reset! connections live))
    (- (count old) (count live))))

(defn connection-count [] (count @connections))

;; ---------------------------------------------------------------------------
;; WebFrame subtree cache. capabilities/substitute hands the server the
;; original subtree it just collapsed; we stash it under a token so the
;; /web-frames/<key>/<token> endpoint can render the actual content as
;; HTML for the WKWebView. FIFO eviction at a generous cap; production
;; deployments that emit lots of WebFrames need a smarter strategy
;; (per-screen TTL, weak refs to connection lifetimes, ...).

(def ^:private webframe-cap 1024)
(defonce webframes (atom clojure.lang.PersistentQueue/EMPTY))
(defonce webframe-trees (atom {}))

(defn stash-webframe! [token tree]
  (swap! webframe-trees assoc token tree)
  (let [q (swap! webframes conj token)]
    (when (> (count q) webframe-cap)
      (let [evict (peek @webframes)]
        (swap! webframes pop)
        (swap! webframe-trees dissoc evict)))))

(defn webframe-tree [token]
  (get @webframe-trees token))

;; ---------------------------------------------------------------------------
;; Intent dedup cache. Bounded LRU keyed by intent id -> response map.
;; Client retries (after a flaky network or a clean reconnect) re-POST
;; intents the server may have already processed; without dedup, an
;; idempotent-from-the-user's-PoV "incremented the counter" intent
;; fires twice. The cache lets us return the same response for the
;; same id without re-running the morph or re-broadcasting.

(def ^:private intent-cache-cap 1024)

(defonce intent-cache
  (atom {:queue clojure.lang.PersistentQueue/EMPTY
         :map   {}}))

(defn cache-intent! [id response]
  (swap! intent-cache
         (fn [{:keys [queue map] :as st}]
           (if (contains? map id)
             st                                      ;; already present; no-op
             (let [q' (conj queue id)
                   m' (assoc map id response)]
               (if (> (count q') intent-cache-cap)
                 (let [evict (peek q')]
                   {:queue (pop q') :map (dissoc m' evict)})
                 {:queue q' :map m'}))))))

(defn cached-intent [id]
  (get-in @intent-cache [:map id]))
