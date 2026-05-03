(ns wun.server.dashboard.tap
  "Telemetry tap that backs the live dev dashboard.

   The framework's existing telemetry sinks (`log-sink`, user-supplied
   Prometheus / OTel sinks, etc.) are write-and-forget: events fan out
   and are gone. The dashboard needs *replay* (recent events list) and
   *aggregation* (per-intent count / mean latency / error rate), so
   we register one more sink that maintains a small bounded snapshot
   of both — read on demand by the screen render fn.

   Bounded by design:
   - `events` keeps the last `event-buffer-size` events (FIFO trim).
   - `metrics` is keyed on intent name, so it grows with the intent
     vocabulary, not with traffic. Counters never roll over; if you
     run a dashboard'd dev server long enough to overflow a long, you
     have other problems.

   Intentionally not here: persistence, time-series buckets, sliding
   windows. Those belong in real metric backends; the dashboard is a
   live snapshot tool, not a Prometheus replacement."
  (:require [wun.server.state     :as state]
            [wun.server.telemetry :as telemetry]
            [wun.components       :as components]
            [wun.screens          :as screens]
            [wun.intents          :as intents]))

(def ^:private event-buffer-size 200)

(defonce ^:private events     (atom clojure.lang.PersistentQueue/EMPTY))
(defonce ^:private metrics    (atom {}))
(defonce ^:private started-at (atom nil))

(defn- trim-queue [q]
  (if (> (count q) event-buffer-size)
    (recur (pop q))
    q))

(defn- record-event! [event-key attrs]
  (swap! events (fn [q]
                  (-> q
                      (conj {:t      (System/currentTimeMillis)
                             :key    event-key
                             :attrs  attrs})
                      trim-queue))))

(defn- bump-intent! [intent-name update-fn]
  (when intent-name
    (swap! metrics update intent-name
           (fnil update-fn {:count 0 :total-ms 0 :errors 0}))))

(defn- update-on-applied [{:keys [duration-ms]}]
  (fn [m]
    (-> m
        (update :count    inc)
        (update :total-ms + (or duration-ms 0)))))

(defn- update-on-rejected [_attrs]
  (fn [m]
    (-> m
        (update :count  inc)
        (update :errors inc))))

(defn- sink [event-key attrs]
  (record-event! event-key attrs)
  (case event-key
    :wun/intent.applied  (bump-intent! (:intent attrs) (update-on-applied attrs))
    :wun/intent.rejected (bump-intent! (:intent attrs) (update-on-rejected attrs))
    nil))

(defn install!
  "Register the dashboard tap sink and stamp `started-at`. Idempotent:
   re-installing on the same JVM is a no-op (`telemetry/register-sink!`
   is identity-deduped). Returns the sink fn."
  []
  (when-not @started-at (reset! started-at (System/currentTimeMillis)))
  (telemetry/register-sink! sink))

(defn uninstall!
  "Unregister the sink and clear collected state. Tests use this to
   keep cross-test isolation; production code never should."
  []
  (telemetry/unregister-sink! sink)
  (reset! events     clojure.lang.PersistentQueue/EMPTY)
  (reset! metrics    {})
  (reset! started-at nil)
  nil)

;; ---------------------------------------------------------------------------
;; Snapshot — pure read-side for the dashboard screen render

(defn- mean-ms [{:keys [count total-ms]}]
  (when (pos? count) (double (/ total-ms count))))

(defn- summarise-intent [[k m]]
  (assoc m
         :intent  k
         :mean-ms (mean-ms m)))

(defn- summarise-conn [[_ch md]]
  {:conn-id (:conn-id md)
   :screen  (peek (:screen-stack md))
   :caps    (:caps md)
   :fmt     (:fmt md)})

(defn snapshot
  "Pure read-side accessor. Returns a map shaped for the dashboard
   screen's render fn. Walks `@state/connections` and the registry
   atoms once per call — cheap at the connection counts a single dev
   box sees, and the dashboard only calls this on its refresh tick."
  []
  (let [now    (System/currentTimeMillis)
        start  (or @started-at now)
        conns  (vals @state/connections)]
    {:uptime-ms      (- now start)
     :active-conns   (count conns)
     :registries     {:components (count (components/registered))
                      :screens    (count @screens/registry)
                      :intents    (count @intents/registry)}
     :connections    (mapv summarise-conn @state/connections)
     :intent-metrics (->> @metrics
                          (mapv summarise-intent)
                          (sort-by (juxt (comp - :count) :intent))
                          vec)
     :recent-events  (vec (reverse (vec @events)))}))
