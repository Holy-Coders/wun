(ns wun.server.telemetry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.telemetry :as t]))

(use-fixtures :each
  (fn [test-fn]
    ;; Replace registered sinks with the empty set for the duration
    ;; so the default log-sink doesn't pollute the test buffer.
    (let [sinks-atom (deref #'t/sinks)
          snap       @sinks-atom]
      (t/clear-sinks!)
      (try (test-fn)
           (finally (reset! sinks-atom snap))))))

(deftest emit-fans-to-registered-sinks
  (let [seen (atom [])
        sink (fn [k attrs] (swap! seen conj [k attrs]))]
    (t/with-sink sink
      (fn []
        (t/emit! :wun/connect    {:conn-id "x"})
        (t/emit! :wun/disconnect {:conn-id "x" :reason :client-closed})))
    (is (= [[:wun/connect    {:conn-id "x"}]
            [:wun/disconnect {:conn-id "x" :reason :client-closed}]]
           @seen))))

(deftest unknown-event-keys-still-fan-out
  (let [seen (atom [])
        sink (fn [k attrs] (swap! seen conj [k attrs]))]
    (t/with-sink sink
      (fn []
        ;; Should warn once + still emit.
        (t/emit! :wun/totally-new {:foo 1})
        (t/emit! :wun/totally-new {:bar 2})))
    (is (= 2 (count @seen)))))

(deftest sink-exception-doesnt-block-other-sinks
  (let [bad  (fn [_ _] (throw (RuntimeException. "boom")))
        seen (atom [])
        good (fn [k a] (swap! seen conj [k a]))]
    (t/register-sink! bad)
    (t/register-sink! good)
    (try
      (t/emit! :wun/connect {:conn-id "x"})
      (is (= [[:wun/connect {:conn-id "x"}]] @seen))
      (finally
        (t/unregister-sink! bad)
        (t/unregister-sink! good)))))

(deftest unregister-removes-sink
  (let [seen (atom 0)
        sink (fn [_ _] (swap! seen inc))]
    (t/register-sink! sink)
    (t/emit! :wun/connect)
    (t/unregister-sink! sink)
    (t/emit! :wun/connect)
    (is (= 1 @seen))))

(deftest event-keys-vocabulary-is-non-trivial
  (is (contains? t/event-keys :wun/connect))
  (is (contains? t/event-keys :wun/intent.received))
  (is (contains? t/event-keys :wun/backpressure.drop))
  (is (contains? t/event-keys :wun/csrf.miss))
  (is (contains? t/event-keys :wun/rate-limit.block)))
