(ns wun.server.state
  (:require [clojure.core.async.impl.protocols :as async-impl]))

;; Single global app state for the spike. Future phases will key state
;; per session / per screen.
(defonce app-state (atom {:counter 0}))

;; SSE connections are keyed by core.async channel and carry the
;; *prior tree* the connection has seen. Diffing happens against this
;; prior in wun.server.http/broadcast-to-channel!. New connections
;; register with prior=nil; diff(nil, current) emits a full
;; :replace-at-root, so the initial frame falls out of the same code
;; path as ongoing broadcasts.
(defonce connections (atom {}))

(defn add-connection! [event-ch]
  (swap! connections assoc event-ch nil))

(defn remove-connection! [event-ch]
  (swap! connections dissoc event-ch))

(defn prior-tree [event-ch]
  (get @connections event-ch))

(defn update-prior-tree! [event-ch tree]
  (swap! connections (fn [m]
                       (if (contains? m event-ch)
                         (assoc m event-ch tree)
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
