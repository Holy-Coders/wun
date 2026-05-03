(ns wun.server.pubsub-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.pubsub :as pub]
            [wun.server.telemetry :as t]))

(use-fixtures :each
  (fn [test-fn]
    (pub/reset-state!)
    (test-fn)
    (pub/reset-state!)))

(deftest subscribe-and-publish-fan-out
  (let [seen-a (atom [])
        seen-b (atom [])]
    (pub/subscribe! :room/r1 (fn [_t p] (swap! seen-a conj p)))
    (pub/subscribe! :room/r1 (fn [_t p] (swap! seen-b conj p)))
    (is (= 2 (pub/publish! :room/r1 {:msg "hello"})))
    (is (= [{:msg "hello"}] @seen-a))
    (is (= [{:msg "hello"}] @seen-b))))

(deftest unrelated-topics-are-isolated
  (let [a (atom 0) b (atom 0)]
    (pub/subscribe! :a (fn [_ _] (swap! a inc)))
    (pub/subscribe! :b (fn [_ _] (swap! b inc)))
    (pub/publish! :a {})
    (pub/publish! :a {})
    (pub/publish! :b {})
    (is (= 2 @a))
    (is (= 1 @b))))

(deftest unsubscribe-stops-delivery
  (let [seen (atom 0)
        tok  (pub/subscribe! :t (fn [_ _] (swap! seen inc)))]
    (pub/publish! :t {})
    (pub/unsubscribe! tok)
    (pub/publish! :t {})
    (is (= 1 @seen))))

(deftest exception-in-subscriber-does-not-block-others
  (let [seen (atom 0)]
    (pub/subscribe! :t (fn [_ _] (throw (RuntimeException. "x"))))
    (pub/subscribe! :t (fn [_ _] (swap! seen inc)))
    (pub/publish! :t {})
    (is (= 1 @seen))))

(deftest emits-telemetry
  (let [counts (atom [])]
    (t/with-sink (fn [k a] (when (= :wun/pubsub.publish k)
                             (swap! counts conj (:n-subscribers a))))
      (fn []
        (pub/subscribe! :x (fn [_ _]))
        (pub/subscribe! :x (fn [_ _]))
        (pub/publish! :x {:hi true})))
    (is (= [2] @counts))))

(deftest publish-with-no-subscribers-returns-zero
  (is (= 0 (pub/publish! :nobody {:msg "void"}))))

(deftest set-bus-swaps-backend
  (let [calls (atom [])
        custom (reify pub/Bus
                 (-subscribe [_ topic f]
                   (swap! calls conj [:sub topic])
                   "custom-token")
                 (-unsubscribe [_ token]
                   (swap! calls conj [:unsub token]))
                 (-publish [_ topic payload]
                   (swap! calls conj [:pub topic payload])
                   42)
                 (-subscribers [_ topic]
                   (swap! calls conj [:count topic])
                   42)
                 (-clear [_]
                   (reset! calls [])))
        prev (pub/current-bus)]
    (pub/set-bus! custom)
    (try
      (let [tok (pub/subscribe! :foo (fn [_ _]))]
        (is (= "custom-token" tok))
        (is (= 42 (pub/publish! :foo {:hi true})))
        (pub/unsubscribe! tok)
        (is (= [[:sub :foo]
                [:pub :foo {:hi true}]
                [:unsub "custom-token"]]
               @calls)))
      (finally
        (pub/set-bus! prev)))))
