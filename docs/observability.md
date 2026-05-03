# Wun observability

Wun ships a vendor-neutral telemetry interface (`wun.server.telemetry`)
that emits one structured event per significant operation. The
default sink writes to `clojure.tools.logging` at info level. To
ship metrics to your platform of choice, register a sink at startup.

## Event vocabulary

```
:wun/connect            {:conn-id :screen-key :caps :fmt :resumed?}
:wun/disconnect         {:conn-id :reason}
:wun/intent.received    {:conn-id :intent :id}
:wun/intent.applied     {:conn-id :intent :id :duration-ms}
:wun/intent.rejected    {:conn-id :intent :id :reason}
:wun/intent.dedup       {:conn-id :intent :id}
:wun/intent.framework   {:conn-id :intent :id :status}
:wun/heartbeat.tick     {:active-conns}
:wun/heartbeat.miss     {:conn-id :since-ms}
:wun/backpressure.drop  {:conn-id}
:wun/backpressure.resync {:conn-id}
:wun/snapshot.resync    {:conn-id :reason}
:wun/csrf.miss          {:conn-id :reason}
:wun/rate-limit.block   {:conn-id :ip :scope :limit}
:wun/upload.start       {:conn-id :upload-id :size}
:wun/upload.progress    {:conn-id :upload-id :received}
:wun/upload.complete    {:conn-id :upload-id}
:wun/upload.error       {:conn-id :upload-id :reason}
:wun/pubsub.publish     {:topic :n-subscribers}
:wun/presence.join      {:topic :conn-id}
:wun/presence.leave     {:topic :conn-id}
```

## Wiring Prometheus

```clj
(require '[wun.server.telemetry :as t]
         '[iapetos.core :as p]
         '[iapetos.collector.fn :as fn-coll])

(def registry
  (-> (p/collector-registry)
      (p/register
       (p/counter   :wun/intents-total      {:labels [:intent :status]})
       (p/histogram :wun/intent-duration-ms {:labels [:intent]})
       (p/gauge     :wun/active-conns       {})
       (p/counter   :wun/csrf-blocks        {})
       (p/counter   :wun/rate-limit-blocks  {:labels [:scope]}))))

(defn prometheus-sink [k attrs]
  (case k
    :wun/intent.applied
    (do (p/inc registry :wun/intents-total
               {:intent (str (:intent attrs)) :status "ok"})
        (p/observe registry :wun/intent-duration-ms
                   {:intent (str (:intent attrs))} (:duration-ms attrs)))

    :wun/intent.rejected
    (p/inc registry :wun/intents-total
           {:intent (str (:intent attrs)) :status "rejected"})

    :wun/heartbeat.tick
    (p/set registry :wun/active-conns {} (:active-conns attrs))

    :wun/csrf.miss
    (p/inc registry :wun/csrf-blocks {})

    :wun/rate-limit.block
    (p/inc registry :wun/rate-limit-blocks {:scope (str (:scope attrs))})

    nil))

(t/register-sink! prometheus-sink)
```

(`iapetos` is just an example -- the contract is "any fn that takes
`(event-key, attrs-map)`"; plug whatever client your shop uses.)

## Wiring OpenTelemetry

```clj
(require '[steffan-westcott.clj-otel.api.metrics.instrument :as inst])

(def intents-counter
  (inst/instrument {:name "wun.intents.total"
                    :instrument-type :counter}))

(defn otel-sink [k attrs]
  (when (= k :wun/intent.applied)
    (inst/add! intents-counter
               {:value 1
                :attributes {:intent (str (:intent attrs))}})))

(wun.server.telemetry/register-sink! otel-sink)
```

## Logging hardening

The default sink calls `tools.logging` which falls through to the
`logback-classic` Wun ships. The default config logs at INFO; in
production, raise to WARN globally and selectively unmute
`wun.server.*` to keep audit trails:

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%logger{32}] %msg%n</pattern>
    </encoder>
  </appender>

  <logger name="wun.server" level="INFO" />
  <logger name="org.eclipse.jetty" level="WARN" />
  <root level="WARN">
    <appender-ref ref="STDOUT" />
  </root>
</configuration>
```

## What NOT to ship to your metrics backend

- `:state` blobs from intent envelopes — they may contain user data.
- Free-text from `:wun/csrf.miss` `:reason` — fine for logs, not for
  per-attribute cardinality in a metrics system.
- Filenames from `:wun/upload.*` events — log them, don't aggregate
  them as labels.

The general rule: anything user-controlled goes in **logs** (high
cardinality, retained briefly) but not in **metrics labels** (low
cardinality, retained long-term).
