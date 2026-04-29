(ns wun.server.state)

;; Single global app state for the phase 0 spike.
;; Future phases will key state per session / per screen.
(defonce app-state (atom {:counter 0}))

;; Open SSE event channels (server -> client). Each is a core.async channel
;; that pedestal's start-event-stream gives us; putting a map onto it emits
;; an SSE event.
(defonce connections (atom #{}))

(defn add-connection! [event-ch]
  (swap! connections conj event-ch))

(defn remove-connection! [event-ch]
  (swap! connections disj event-ch))
