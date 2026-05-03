(ns wun.server.heartbeat-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.server.heartbeat :as hb]
            [wun.server.telemetry :as t]))

(deftest ping-envelope-shape
  (let [env (hb/ping-envelope 1717000000)]
    (is (= :ping (:type env)))
    (is (= 1717000000 (:ts env)))))

(deftest stale-when-older-than-multiplier-of-interval
  (is (true?  (hb/stale? 0     30000 10 2)))
  (is (false? (hb/stale? 25000 30000 10 2)))
  (is (false? (hb/stale? 0     5000  10 2)))
  (is (nil?   (hb/stale? nil   30000 10 2))))

(deftest tick-emits-telemetry
  (let [seen (atom [])]
    (t/with-sink (fn [k a] (when (= :wun/heartbeat.tick k) (swap! seen conj a)))
      (fn []
        (hb/record-tick! 7)))
    (is (= [{:active-conns 7}] @seen))))

(deftest miss-emits-telemetry
  (let [seen (atom [])]
    (t/with-sink (fn [k a] (when (= :wun/heartbeat.miss k) (swap! seen conj a)))
      (fn []
        (hb/record-miss! "cid-Z" 31000)))
    (is (= [{:conn-id "cid-Z" :since-ms 31000}] @seen))))

(deftest interval-defaults-when-env-absent
  ;; The actual env var isn't set in the test env, so the default
  ;; applies. This test documents the contract more than it exercises
  ;; logic.
  (is (= 25 hb/default-interval-secs)))
