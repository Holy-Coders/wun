(ns wun.server.heartbeat
  "SSE heartbeat: periodic `{:type :ping :ts ms}` envelopes pushed to
   every live connection. Two purposes:

   1. Cuts through dead-but-not-yet-detected proxies. NAT/load-balancer
      idle timeouts kill TCP sockets after a few minutes of silence;
      a heartbeat every N seconds keeps them open. The default 25s
      sits below the typical 60s LB idle window.

   2. Gives the client a watchdog signal. Web client treats absence of
      any frame for `2 * heartbeat-interval-secs` as a dead conn and
      forces a reconnect; native clients do the same. This catches the
      case where the server thinks the conn is fine but the bytes
      aren't flowing.

   The heartbeat envelope is *separate* from a patch envelope so the
   client can ignore it for diff/state purposes -- it just resets the
   watchdog. Wire shape:

       {:type :ping :ts <epoch-ms>}

   Pure functions live here; the scheduling (and the actual `offer!`
   into the SSE channel) lives in the wiring layer that owns Pedestal,
   so this namespace stays unit-testable without an HTTP transport."
  (:require [wun.server.telemetry :as telemetry]))

(def default-interval-secs
  "Default heartbeat cadence. 25s sits below the typical 60s
   load-balancer idle timeout but doesn't burn bandwidth. Override via
   the `WUN_HEARTBEAT_INTERVAL_SECS` env var."
  25)

(def default-stale-multiplier
  "How many heartbeat intervals can elapse without any traffic before
   we consider the connection stale and trigger a snapshot resync.
   2 means: miss two consecutive heartbeats and the client gets a
   forced full re-render."
  2)

(defn interval-secs
  "Resolve the configured interval, env var taking precedence."
  []
  (or (some-> (System/getenv "WUN_HEARTBEAT_INTERVAL_SECS")
              Integer/parseInt)
      default-interval-secs))

(defn ping-envelope
  "Build a ping envelope. `ts` is the wall-clock millis the client
   uses to reset its watchdog and (optionally) compute clock skew."
  [ts]
  {:type :ping :ts ts})

(defn stale?
  "True when `last-frame-ms` is older than `interval-secs *
   stale-multiplier` seconds before `now-ms`. Used by the connection
   sweeper to decide which conns need a snapshot resync."
  ([last-frame-ms now-ms]
   (stale? last-frame-ms now-ms (interval-secs) default-stale-multiplier))
  ([last-frame-ms now-ms interval stale-multiplier]
   (when last-frame-ms
     (> (- now-ms last-frame-ms)
        (* interval stale-multiplier 1000)))))

(defn record-tick!
  "Hook for the scheduling layer to call when it pushes a heartbeat to
   `active-conns` channels. Pure-data; just emits telemetry."
  [active-conns]
  (telemetry/emit! :wun/heartbeat.tick {:active-conns active-conns}))

(defn record-miss!
  "Hook for the scheduling layer to call when a connection is detected
   as stale. Emits a telemetry event so the deployer can alert on it."
  [conn-id since-ms]
  (telemetry/emit! :wun/heartbeat.miss {:conn-id conn-id :since-ms since-ms}))
