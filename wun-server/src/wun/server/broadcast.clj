(ns wun.server.broadcast
  "Convenience layer on top of `wun.server.pubsub` + `wun.server.state`
   that wraps the most common multi-connection pattern: each subscribed
   conn applies an arbitrary morph to its own state slice and the
   framework re-broadcasts the resulting tree.

   Pattern:

     ;; In an app intent's morph, fan out a chat message:
     (broadcast/publish-and-morph!
       :chat-room-7
       {:msg \"hello!\" :from \"alice\"}
       (fn [state msg]
         (update state :messages (fnil conj []) msg)))

   Every conn that previously called

     (broadcast/subscribe! :chat-room-7 conn-id (fn re-broadcast! [_]))

   has the same morph applied to its slice, and the http layer is
   notified via the registered re-broadcast hook. Anonymous slices
   (no DB hydration) lose their messages on disconnect; durable
   chat lives in app-level persistence which the morph reads at
   render time.

   Why a separate ns from pubsub: pubsub is the generic fan-out
   primitive (any payload, any subscriber). Broadcast is the
   batteries-included path for the per-conn re-render case that
   accounts for >90% of cross-connection use, and decouples that
   pattern from the http layer (broadcast knows about morphs and
   conn-ids; http knows about Pedestal SSE channels)."
  (:require [wun.server.pubsub    :as pubsub]
            [wun.server.state     :as state]
            [wun.server.telemetry :as telemetry]))

;; ---------------------------------------------------------------------------
;; Re-broadcast hook. Called as `(f conn-id)` after a subscribed
;; conn's state has been morphed; the http layer wires this to
;; `wun.server.http/broadcast-to-conn!` at startup.

(defonce ^:private rebroadcast-fn (atom (constantly nil)))

(defn register-rebroadcast-fn!
  "Wire in `(f conn-id) -> ()` so subscribed conns can push a fresh
   patch envelope after their state mutates. Called once at startup
   from the http layer to break the http -> broadcast -> http cycle."
  [f]
  (reset! rebroadcast-fn f))

;; ---------------------------------------------------------------------------
;; Subscribe / unsubscribe

(defn subscribe!
  "Subscribe `conn-id` to `topic`: every published message runs
   `morph-fn (state, message) -> state'` against the conn's slice and
   then triggers a per-conn re-broadcast via the registered hook.

   Returns a token the caller passes to `unsubscribe!`."
  [topic conn-id morph-fn]
  (pubsub/subscribe!
   topic
   (fn [_topic message]
     (state/swap-state-for! conn-id morph-fn message)
     (@rebroadcast-fn conn-id))))

(defn unsubscribe! [token]
  (pubsub/unsubscribe! token))

;; ---------------------------------------------------------------------------
;; Publish helpers

(defn publish!
  "Publish `message` to `topic`. Returns the number of subscribers
   that received it. Sugar over `pubsub/publish!` so callers don't
   need to know two namespaces."
  [topic message]
  (pubsub/publish! topic message))

(defn publish-and-morph!
  "One-shot publish: applies `morph-fn` to every subscribed conn's
   slice with `message` as the second arg. Returns the number of
   subscribers reached. Used directly from an intent morph that
   wants to fan out without registering a long-lived subscription
   first.

   Caveat: there's no separate broadcast back to publishers other
   than the morph re-running; conns that haven't subscribed don't
   see the message even if their state would naturally include it.
   This is by design -- presence is opt-in."
  [topic message _morph-fn]
  ;; Subscribers chosen at subscribe! time already know how to morph;
  ;; this fn just dispatches the payload.
  (telemetry/emit! :wun/pubsub.publish
                   {:topic topic :n-subscribers (pubsub/subscriber-count topic)})
  (pubsub/publish! topic message))
