(ns wun.server.backpressure-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.backpressure :as bp]
            [wun.server.telemetry :as t]))

(use-fixtures :each
  (fn [test-fn]
    (bp/reset-state!)
    (test-fn)
    (bp/reset-state!)))

(deftest mark-and-stale-and-clear
  (is (not (bp/stale? "cid-A")))
  (bp/mark-stale! "cid-A")
  (is (bp/stale? "cid-A"))
  (bp/clear-stale! "cid-A")
  (is (not (bp/stale? "cid-A"))))

(deftest mark-stale-is-idempotent
  (let [seen (atom 0)]
    (t/with-sink (fn [k _] (when (= :wun/backpressure.drop k) (swap! seen inc)))
      (fn []
        (bp/mark-stale! "cid-A")
        (bp/mark-stale! "cid-A")
        (bp/mark-stale! "cid-A")))
    (is (= 1 @seen))))

(deftest clear-stale-no-op-when-not-stale
  (let [seen (atom 0)]
    (t/with-sink (fn [k _] (when (= :wun/backpressure.resync k) (swap! seen inc)))
      (fn []
        (bp/clear-stale! "cid-A")))
    (is (zero? @seen))))

(deftest clear-stale-emits-resync
  (let [seen (atom [])]
    (t/with-sink (fn [k a] (when (= :wun/backpressure.resync k)
                             (swap! seen conj a)))
      (fn []
        (bp/mark-stale! "cid-A")
        (bp/clear-stale! "cid-A")))
    (is (= [{:conn-id "cid-A"}] @seen))))

(deftest handle-offer-passes-through-when-accepted
  (is (true?  (bp/handle-offer! "cid-A" true)))
  (is (not (bp/stale? "cid-A"))))

(deftest handle-offer-marks-stale-on-reject
  (is (false? (bp/handle-offer! "cid-A" false)))
  (is (bp/stale? "cid-A")))

(deftest drop-conn-forgets-state
  (bp/mark-stale! "cid-A")
  (bp/drop-conn! "cid-A")
  (is (not (bp/stale? "cid-A"))))
