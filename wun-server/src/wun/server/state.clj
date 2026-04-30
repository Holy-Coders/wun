(ns wun.server.state
  (:require [clojure.core.async.impl.protocols :as async-impl]))

;; Single global app state for the spike. Future phases will key state
;; per session / per screen.
(defonce app-state (atom {:counter 0}))

;; SSE connections are keyed by core.async channel and carry per-conn
;; metadata: the *prior tree* the connection has seen (for diffing),
;; the *caps* the client advertised (for capability substitution),
;; the wire *fmt* (:transit or :json), the *screen-stack* (top is the
;; currently-rendered screen; phase 6.C navigation pushes/pops here),
;; and a *conn-id* the client echoes on /intent POSTs so the server
;; can route framework intents (`:wun/navigate`, `:wun/pop`) back to
;; the originating connection.
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
