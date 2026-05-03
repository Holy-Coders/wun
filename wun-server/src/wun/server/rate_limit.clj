(ns wun.server.rate-limit
  "Token-bucket rate limiter for the /intent endpoint.

   Two scopes share the same algorithm:
     :conn  -- per server-issued conn-id (cheap, unforgeable since the
               server gates the SSE handshake)
     :ip    -- per source IP (forgeable behind a NAT but still bounds
               the worst case for unauthenticated burst traffic)

   Algorithm: classic token bucket. Each scoped key has a bucket with
   `capacity` tokens and refills at `refill-per-sec`. A request costs
   one token; an empty bucket means the request is rejected (the
   caller maps that to HTTP 429).

   Why token bucket over fixed-window: fixed windows let bursts at the
   window boundary spike to 2x the limit; token bucket enforces a true
   sustained rate while still allowing bursts up to capacity.

   State lives in-process. Production deploys with multiple replicas
   should layer a shared-state limiter (Redis INCR / sliding-window) in
   front of this; the per-process limiter still serves as a backstop.

   Buckets are evicted lazily after `idle-evict-ms` of no traffic so
   the map doesn't grow without bound for a long-running server."
  (:require [wun.server.telemetry :as telemetry]))

;; ---------------------------------------------------------------------------
;; Config -- mutable so callers can tune without restart.

(defonce config
  (atom {:conn {:capacity 60 :refill-per-sec 30}
         :ip   {:capacity 120 :refill-per-sec 60}
         :idle-evict-ms 60000}))

(defn configure!
  "Override default rate-limit config. `m` is merged into the current
   config; nested maps are not deep-merged (replace whole sub-map)."
  [m]
  (swap! config merge m))

;; ---------------------------------------------------------------------------
;; State

(defonce ^:private buckets (atom {}))

(defn reset-state!
  "Clear all bucket state. Tests use this between cases; production
   code never does."
  []
  (reset! buckets {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- refill [bucket {:keys [capacity refill-per-sec]} now]
  (let [elapsed-s (max 0 (/ (double (- now (:last bucket))) 1000.0))
        added     (* elapsed-s refill-per-sec)
        tokens'   (min (double capacity) (+ (:tokens bucket) added))]
    (assoc bucket :tokens tokens' :last now)))

(defn- bucket-key [scope id] [scope id])

(defn- step
  "Apply the token-bucket logic to `bucket` (or seed a fresh one) and
   return `[new-bucket allowed?]`. Cost is always 1; supporting
   variable cost is a future extension if we ever want bigger
   intents (uploads) to spend more tokens."
  [bucket scope-cfg now]
  (let [b (or bucket {:tokens (:capacity scope-cfg) :last now})
        b (refill b scope-cfg now)]
    (if (>= (:tokens b) 1.0)
      [(update b :tokens - 1.0) true]
      [b false])))

(defn allow?
  "Check + decrement the token bucket for `[scope id]`. Returns true
   when the request is allowed, false when it should be rejected.
   Emits `:wun/rate-limit.block` on rejection."
  ([scope id] (allow? scope id (now-ms)))
  ([scope id now]
   (let [scope-cfg (get @config scope)
         _         (when-not scope-cfg
                     (throw (ex-info "Unknown rate-limit scope" {:scope scope})))
         result    (atom nil)]
     (swap! buckets
            (fn [m]
              (let [k        (bucket-key scope id)
                    [b ok?]  (step (get m k) scope-cfg now)]
                (reset! result ok?)
                (assoc m k b))))
     (when-not @result
       (telemetry/emit! :wun/rate-limit.block
                        {:scope scope :id id :limit (:capacity scope-cfg)}))
     @result)))

;; ---------------------------------------------------------------------------
;; Eviction

(defn evict-idle!
  "Drop bucket entries that haven't been touched in `idle-evict-ms`.
   Returns the number of buckets evicted. Call from the same scheduled
   pool that does connection GC."
  ([] (evict-idle! (now-ms)))
  ([now]
   (let [{:keys [idle-evict-ms]} @config
         cutoff (- now idle-evict-ms)
         old    @buckets
         live   (into {} (remove (fn [[_ b]] (< (:last b) cutoff)) old))]
     (when (not= (count old) (count live))
       (reset! buckets live))
     (- (count old) (count live)))))
