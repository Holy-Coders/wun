(ns wun.server.telemetry
  "Vendor-neutral telemetry interface. Wun emits structured events for
   connect / disconnect / intent / heartbeat / backpressure / csrf /
   rate-limit; deployers wire those into Prometheus / OpenTelemetry /
   their log aggregator of choice by registering a sink fn.

   A *sink* is `(event-key, attrs-map) -> nil`. Sinks are called in
   registration order; exceptions thrown by one sink are caught and
   logged via `clojure.tools.logging` so a misbehaving sink can't
   block the hot path.

   Default sinks: a single `log-sink` is installed at namespace load
   that writes one line per event via `tools.logging` at info level.
   Tests use `with-sink` to capture events into an atom and inspect
   them; production deployments register their own metric / trace
   sinks at startup.

   Event keys are namespaced and form a small, stable vocabulary --
   adding a new event key is a contract change, not a free-for-all.
   See `event-keys` for the canonical list."
  (:require [clojure.tools.logging :as log]))

(def event-keys
  "The canonical set of telemetry event keys Wun emits. Adding to this
   set is a public contract change -- downstream sinks can rely on the
   schema being stable."
  #{:wun/connect            ;; {:conn-id :screen-key :caps :fmt :resumed?}
    :wun/disconnect         ;; {:conn-id :reason}
    :wun/intent.received    ;; {:conn-id :intent :id}
    :wun/intent.applied     ;; {:conn-id :intent :id :duration-ms}
    :wun/intent.rejected    ;; {:conn-id :intent :id :reason}
    :wun/intent.dedup       ;; {:conn-id :intent :id}
    :wun/intent.framework   ;; {:conn-id :intent :id :status}
    :wun/heartbeat.tick     ;; {:active-conns}
    :wun/heartbeat.miss     ;; {:conn-id :since-ms}
    :wun/backpressure.drop  ;; {:conn-id}
    :wun/backpressure.resync ;; {:conn-id}
    :wun/snapshot.resync    ;; {:conn-id :reason}
    :wun/csrf.miss          ;; {:conn-id :reason}
    :wun/rate-limit.block   ;; {:conn-id :ip :scope :limit}
    :wun/upload.start       ;; {:conn-id :upload-id :size}
    :wun/upload.progress    ;; {:conn-id :upload-id :received}
    :wun/upload.complete    ;; {:conn-id :upload-id}
    :wun/upload.error       ;; {:conn-id :upload-id :reason}
    :wun/pubsub.publish     ;; {:topic :n-subscribers}
    :wun/presence.join      ;; {:topic :conn-id}
    :wun/presence.leave     ;; {:topic :conn-id}
    })

;; ---------------------------------------------------------------------------
;; Sinks

(defonce ^:private sinks (atom []))

(defn register-sink!
  "Register a sink fn `(event-key, attrs-map) -> nil`. Returns a token
   the caller can pass to `unregister-sink!`. Idempotent on identity:
   re-registering the same fn returns the original token without
   inserting a duplicate."
  [f]
  (swap! sinks (fn [s] (if (some #{f} s) s (conj s f))))
  f)

(defn unregister-sink! [f]
  (swap! sinks (fn [s] (vec (remove #{f} s))))
  nil)

(defn clear-sinks!
  "Remove all registered sinks. Tests use this; production code never
   should."
  []
  (reset! sinks []))

;; ---------------------------------------------------------------------------
;; Emission

(let [seen (atom #{})]
  (defn- warn-unknown-event-key [k]
    (when-not (contains? @seen k)
      (swap! seen conj k)
      (log/warnf "wun.telemetry: unknown event key %s -- consider adding to event-keys" k))))

(defn emit!
  "Fan out `event-key` + `attrs` to every registered sink. Unknown
   event keys log a warning once per JVM but are otherwise allowed --
   the cost of dropping a real event because the registry hasn't
   caught up is higher than the cost of a stray key in a metric."
  ([event-key] (emit! event-key {}))
  ([event-key attrs]
   (when-not (contains? event-keys event-key)
     (warn-unknown-event-key event-key))
   (doseq [f @sinks]
     (try (f event-key attrs)
          (catch Throwable t
            (log/warn t "wun.telemetry: sink threw"))))))

;; ---------------------------------------------------------------------------
;; Test helper

(defn with-sink
  "Run `f` with `sink` registered, then unregister. Returns whatever
   `f` returns. Useful in tests where a teardown is implicit in the
   try/finally."
  [sink f]
  (register-sink! sink)
  (try (f)
       (finally (unregister-sink! sink))))

;; ---------------------------------------------------------------------------
;; Default sinks

(defn log-sink
  "Default sink: log every event at info level via tools.logging.
   Format: `wun.event :wun/connect {:conn-id ... :caps ...}`. Pass
   structured logging frameworks a more appropriate sink at startup.
   Wun installs this at namespace load so a fresh server has at least
   audit-trail visibility out of the box."
  [event-key attrs]
  (log/infof "wun.event %s %s" event-key (pr-str attrs)))

(defonce ^:private install-default-sink
  (delay (register-sink! log-sink)))

@install-default-sink
