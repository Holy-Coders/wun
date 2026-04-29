(ns wun.server.state)

;; Single global app state for the spike. Future phases will key state
;; per session / per screen.
(defonce app-state (atom {:counter 0}))

;; SSE connections are now keyed by core.async channel and carry the
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
