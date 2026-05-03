(ns wun.server.presence
  "Phoenix-Presence-equivalent: per-topic set of `{conn-id, meta}`
   entries with deterministic join / leave broadcasts.

   The model: a topic is an opaque value (typically a screen-id or
   chat-room-id). Every connection that wants to be visible on the
   topic calls `join!` with its conn-id and a metadata map (e.g.
   `{:user-id 7 :name \"Aaron\"}`). The set is mirrored to all
   subscribers of the topic via `wun.server.pubsub` so other
   connections can re-render. On disconnect the framework calls
   `leave-all!` so stale entries are reaped automatically.

   Why a separate namespace from pubsub: the pubsub bus is generic
   (any topic, any payload). Presence is a specific schema on top of
   the same bus, with its own join/leave events and a query API
   `(list topic) -> #{...entries}`. Keeping it separate means apps
   that don't need presence don't pay for its state."
  (:require [wun.server.pubsub :as pubsub]
            [wun.server.telemetry :as telemetry]))

;; ---------------------------------------------------------------------------
;; State -- topic -> {conn-id -> meta}.

(defonce ^:private rolls (atom {}))

(defn reset-state!
  "Clear all presence rolls. Tests use this between cases; production
   code never should."
  []
  (reset! rolls {}))

;; ---------------------------------------------------------------------------
;; API

(defn join!
  "Add `conn-id` to `topic`'s roll with the given `meta` map. Replaces
   any existing entry for the same conn-id (so meta updates are
   idempotent). Publishes `{:event :join :conn-id ... :meta ...}`
   to the pubsub topic so other subscribers re-render."
  ([topic conn-id] (join! topic conn-id {}))
  ([topic conn-id meta]
   (swap! rolls update topic (fnil assoc {}) conn-id (or meta {}))
   (telemetry/emit! :wun/presence.join {:topic topic :conn-id conn-id})
   (pubsub/publish! topic {:event   :join
                           :conn-id conn-id
                           :meta    meta
                           :roll    (get @rolls topic)})
   nil))

(defn leave!
  "Drop `conn-id` from `topic`'s roll. Idempotent: leaving an absent
   conn is a no-op (no telemetry, no broadcast)."
  [topic conn-id]
  (let [present? (contains? (get @rolls topic) conn-id)]
    (when present?
      (swap! rolls update topic dissoc conn-id)
      (telemetry/emit! :wun/presence.leave {:topic topic :conn-id conn-id})
      (pubsub/publish! topic {:event   :leave
                              :conn-id conn-id
                              :roll    (get @rolls topic)}))
    nil))

(defn leave-all!
  "Drop `conn-id` from every topic. Called by the framework when an
   SSE connection closes so stale presence entries are reaped
   automatically. Returns the set of topics the conn was on."
  [conn-id]
  (let [touched (atom #{})]
    (swap! rolls
           (fn [m]
             (reduce-kv (fn [acc topic subs]
                          (if (contains? subs conn-id)
                            (do (swap! touched conj topic)
                                (let [subs' (dissoc subs conn-id)]
                                  (if (seq subs')
                                    (assoc acc topic subs')
                                    acc)))
                            (assoc acc topic subs)))
                        {} m)))
    (doseq [topic @touched]
      (telemetry/emit! :wun/presence.leave {:topic topic :conn-id conn-id})
      (pubsub/publish! topic {:event   :leave
                              :conn-id conn-id
                              :roll    (get @rolls topic)}))
    @touched))

(defn list-topic
  "Return the current roll for `topic`: a map of `{conn-id -> meta}`."
  [topic]
  (get @rolls topic {}))

(defn count-topic
  "Number of conns currently present on `topic`."
  [topic]
  (count (get @rolls topic)))

(defn topics-of
  "Return the set of topics `conn-id` is present on."
  [conn-id]
  (into #{}
        (keep (fn [[topic subs]]
                (when (contains? subs conn-id) topic)))
        @rolls))
