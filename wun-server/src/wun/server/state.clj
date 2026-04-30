(ns wun.server.state
  (:require [clojure.core.async.impl.protocols :as async-impl]))

;; Single global app state for the spike. Future phases will key state
;; per session / per screen.
(defonce app-state (atom {:counter 0}))

;; SSE connections are keyed by core.async channel and carry per-conn
;; metadata: the *prior tree* the connection has seen (for diffing),
;; the *caps* the client advertised (for capability substitution),
;; and the wire *fmt* (:transit or :json) the client requested.
;; New connections register with prior=nil; diff(nil, current) emits
;; a full :replace-at-root, so the initial frame falls out of the
;; same code path as ongoing broadcasts.
(defonce connections (atom {}))

(defn add-connection! [event-ch caps fmt screen-key]
  (swap! connections assoc event-ch {:prior      nil
                                     :caps       caps
                                     :fmt        fmt
                                     :screen-key screen-key}))

(defn remove-connection! [event-ch]
  (swap! connections dissoc event-ch))

(defn prior-tree [event-ch]
  (get-in @connections [event-ch :prior]))

(defn caps [event-ch]
  (get-in @connections [event-ch :caps]))

(defn fmt [event-ch]
  (get-in @connections [event-ch :fmt]))

(defn screen-key [event-ch]
  (get-in @connections [event-ch :screen-key]))

(defn update-prior-tree! [event-ch tree]
  (swap! connections (fn [m]
                       (if (contains? m event-ch)
                         (assoc-in m [event-ch :prior] tree)
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
