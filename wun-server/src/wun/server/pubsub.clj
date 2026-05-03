(ns wun.server.pubsub
  "Cross-connection broadcast bus. Per-conn morphs are pure; if a
   chat-room intent on conn A needs to push a patch into conn B's
   tree, A publishes on a topic and the framework dispatches an
   internal morph on every subscribed conn to re-broadcast.

   Wun ships an in-process `default-bus` -- an atom-of-subscribers --
   that's perfect for single-process deploys. Multi-replica deploys
   plug a remote backend (Redis pub/sub, NATS, Postgres LISTEN/NOTIFY)
   by implementing the small `Bus` protocol below and calling
   `set-bus!`. The framework only depends on the protocol; it doesn't
   know or care whether the wire underneath is a local atom or a
   network round trip.

   Subscriber fn signature: `(topic, payload) -> ()`. Subscribers run
   on the publisher's thread; expensive work belongs on a separate
   thread the subscriber spawns. Failed subscribers surface as
   logged warnings -- a misbehaving subscriber can't take the bus
   down.

   Topics are arbitrary opaque values (typically keywords), but
   keyword discipline keeps things searchable: namespace by feature
   (`:my-chat-room/123`, `:dashboard/team-7`)."
  (:require [clojure.tools.logging :as log]
            [wun.server.telemetry  :as telemetry]))

;; ---------------------------------------------------------------------------
;; Bus protocol -- pluggable backend.

(defprotocol Bus
  (-subscribe   [this topic f]
    "Register `f` for `topic`. Returns an opaque token for unsubscribe.")
  (-unsubscribe [this token]
    "Drop the subscription identified by `token`.")
  (-publish     [this topic payload]
    "Synchronously deliver `payload` to every subscriber on `topic`.")
  (-subscribers [this topic]
    "Return a seq (or count) of current subscribers; introspection.")
  (-clear       [this]
    "Drop every subscription. Tests use this; production code never
     should."))

;; ---------------------------------------------------------------------------
;; In-process default bus.

(defrecord InProcessBus [subscribers]
  Bus
  (-subscribe [_ topic f]
    (let [token (str (java.util.UUID/randomUUID))]
      (swap! subscribers update topic
             (fnil assoc {}) token f)
      token))

  (-unsubscribe [_ token]
    (swap! subscribers
           (fn [m]
             (reduce-kv (fn [acc topic subs]
                          (let [subs' (dissoc subs token)]
                            (if (seq subs')
                              (assoc acc topic subs')
                              acc)))
                        {} m))))

  (-publish [_ topic payload]
    (let [subs (vals (get @subscribers topic))]
      (telemetry/emit! :wun/pubsub.publish
                       {:topic topic :n-subscribers (count subs)})
      (doseq [f subs]
        (try (f topic payload)
             (catch Throwable t
               (log/warn t "wun.pubsub: subscriber threw on" topic))))
      (count subs)))

  (-subscribers [_ topic]
    (count (get @subscribers topic)))

  (-clear [_]
    (reset! subscribers {})))

(defn in-process-bus []
  (->InProcessBus (atom {})))

;; ---------------------------------------------------------------------------
;; Active bus.

(defonce ^:private bus (atom (in-process-bus)))

(defn set-bus!
  "Swap the active bus. Used by the per-app integration layer to plug
   in a Redis / NATS / SQS-backed implementation. The current bus is
   replaced wholesale; existing subscriptions on the old bus are
   abandoned (callers responsible for migration if needed)."
  [new-bus]
  (reset! bus new-bus)
  new-bus)

(defn current-bus [] @bus)

(defn reset-state!
  "Clear all subscribers on the active bus. Tests use this between
   cases; production code never should."
  []
  (-clear @bus))

;; ---------------------------------------------------------------------------
;; Public API

(defn subscribe!
  "Register `f` `(topic, payload) -> ()` for `topic`. Returns a token
   the caller passes to `unsubscribe!`."
  [topic f]
  (-subscribe @bus topic f))

(defn unsubscribe! [token]
  (-unsubscribe @bus token))

(defn publish!
  "Synchronously deliver `payload` to every subscriber on `topic`.
   Returns the number of subscribers that received it."
  [topic payload]
  (-publish @bus topic payload))

(defn subscriber-count [topic]
  (-subscribers @bus topic))
