(ns wun.server.backpressure
  "Backpressure policy for SSE patch enqueue.

   The flow: each connection has a Pedestal-managed core.async channel
   we `offer!` envelopes to. `offer!` is non-blocking -- it returns
   true if the buffer accepted the value, false if the buffer is full.
   A false here means the client isn't draining fast enough; the
   options are:

     a) drop the patch silently       -- client desyncs, undetectable
     b) close the connection          -- aggressive, loses session
     c) mark the connection :stale,
        force a snapshot resync on
        the next successful offer    -- what we do

   Option (c) keeps the connection alive but accepts the temporary
   inefficiency of resending the whole tree. The trade is correctness
   over wire economy: a desync is a bug we can't observe.

   This namespace exposes the policy as pure-data hooks so the
   wiring layer (wun.server.http) can call `mark-stale!` /
   `clear-stale!` / `stale?` against any connection registry. The
   resync mechanic is: when a connection is :stale, the broadcast
   path forces `prior-tree` to nil, so the next diff produces a
   full `:replace` at root (the existing diff implementation already
   does this naturally for nil priors)."
  (:require [wun.server.telemetry :as telemetry]))

;; ---------------------------------------------------------------------------
;; Per-connection stale flag. Stored separately from the connections
;; map so callers can mutate it without re-reading the whole entry.

(defonce ^:private stale-conns (atom #{}))

(defn reset-state!
  "Clear all stale flags. Tests use this between cases."
  []
  (reset! stale-conns #{}))

(defn mark-stale!
  "Mark `conn-id` as stale. Idempotent: re-marking is a no-op. Emits
   a telemetry event the first time a conn becomes stale within a
   given run so dashboards can alert on persistent backpressure."
  [conn-id]
  (let [was-stale? (contains? @stale-conns conn-id)]
    (swap! stale-conns conj conn-id)
    (when-not was-stale?
      (telemetry/emit! :wun/backpressure.drop {:conn-id conn-id}))))

(defn clear-stale!
  "Clear `conn-id`'s stale flag, typically called after a successful
   resync envelope ships. Emits `:wun/backpressure.resync`."
  [conn-id]
  (when (contains? @stale-conns conn-id)
    (swap! stale-conns disj conn-id)
    (telemetry/emit! :wun/backpressure.resync {:conn-id conn-id})))

(defn stale?
  "True when `conn-id` has been marked stale and not yet resynced."
  [conn-id]
  (contains? @stale-conns conn-id))

(defn drop-conn!
  "Forget about `conn-id` entirely (called on disconnect)."
  [conn-id]
  (swap! stale-conns disj conn-id))

;; ---------------------------------------------------------------------------
;; Helpers for the wiring layer.

(defn handle-offer!
  "Wrap an `(offer! ch envelope)` call. Returns true on accept, false
   on reject. On reject, marks `conn-id` stale so the next broadcast
   path forces a resync. The wiring layer is responsible for the
   actual `offer!`; this fn keeps the bookkeeping in one place."
  [conn-id offered?]
  (if offered?
    true
    (do (mark-stale! conn-id)
        false)))
