(ns wun.server.dashboard.tap-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.dashboard.tap :as tap]
            [wun.server.telemetry     :as telemetry]))

(use-fixtures :each
  (fn [test-fn]
    (telemetry/clear-sinks!)
    (tap/uninstall!)
    (try (tap/install!)
         (test-fn)
         (finally
           (tap/uninstall!)
           (telemetry/clear-sinks!)))))

(deftest snapshot-empty-on-fresh-install
  (let [s (tap/snapshot)]
    (is (= 0 (:active-conns s)))
    (is (zero? (count (:recent-events s))))
    (is (zero? (count (:intent-metrics s))))
    (is (>= (:uptime-ms s) 0))
    (is (map? (:registries s)))))

(deftest events-flow-into-recent-buffer
  (telemetry/emit! :wun/connect    {:conn-id "a"})
  (telemetry/emit! :wun/disconnect {:conn-id "a" :reason :client-closed})
  (let [evs (:recent-events (tap/snapshot))]
    (is (= 2 (count evs)))
    ;; recent-events is newest-first
    (is (= :wun/disconnect (-> evs first  :key)))
    (is (= :wun/connect    (-> evs second :key)))))

(deftest ring-buffer-trims-oldest
  (dotimes [i 250]
    (telemetry/emit! :wun/heartbeat.tick {:active-conns i}))
  (let [evs (:recent-events (tap/snapshot))]
    (is (= 200 (count evs)))
    ;; The oldest 50 should have been trimmed.
    (is (>= (-> evs last :attrs :active-conns) 50))))

(deftest intent-applied-aggregates-count-and-mean-latency
  (telemetry/emit! :wun/intent.applied {:conn-id "a" :intent :counter/inc :duration-ms 2})
  (telemetry/emit! :wun/intent.applied {:conn-id "a" :intent :counter/inc :duration-ms 4})
  (telemetry/emit! :wun/intent.applied {:conn-id "a" :intent :counter/inc :duration-ms 6})
  (let [m (->> (tap/snapshot) :intent-metrics (filter (comp #{:counter/inc} :intent)) first)]
    (is (= 3 (:count m)))
    (is (zero? (:errors m)))
    (is (= 4.0 (:mean-ms m)))))

(deftest intent-rejected-bumps-error-counter
  (telemetry/emit! :wun/intent.applied  {:conn-id "a" :intent :foo/bar :duration-ms 1})
  (telemetry/emit! :wun/intent.rejected {:conn-id "a" :intent :foo/bar :reason :invalid-params})
  (telemetry/emit! :wun/intent.rejected {:conn-id "a" :intent :foo/bar :reason :invalid-params})
  (let [m (->> (tap/snapshot) :intent-metrics (filter (comp #{:foo/bar} :intent)) first)]
    (is (= 3 (:count m))) ;; rejected calls also increment :count
    (is (= 2 (:errors m)))))

(deftest intent-metrics-sorted-by-call-volume-desc
  (telemetry/emit! :wun/intent.applied {:intent :rare    :duration-ms 1})
  (dotimes [_ 5] (telemetry/emit! :wun/intent.applied {:intent :common :duration-ms 1}))
  (let [order (mapv :intent (:intent-metrics (tap/snapshot)))]
    (is (= [:common :rare] order))))

(deftest uninstall-clears-buffers
  (telemetry/emit! :wun/intent.applied {:intent :a :duration-ms 1})
  (is (pos? (count (:recent-events  (tap/snapshot)))))
  (tap/uninstall!)
  (tap/install!)
  (is (zero? (count (:recent-events  (tap/snapshot)))))
  (is (zero? (count (:intent-metrics (tap/snapshot))))))

(deftest install-is-idempotent-on-the-sink-set
  (let [before (count (deref (deref #'telemetry/sinks)))]
    (tap/install!)
    (tap/install!)
    (tap/install!)
    (is (= before (count (deref (deref #'telemetry/sinks)))))))
