(ns wun.server.presence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.presence :as p]
            [wun.server.pubsub :as pub]))

(use-fixtures :each
  (fn [t]
    (p/reset-state!)
    (pub/reset-state!)
    (try (t)
         (finally
           (p/reset-state!)
           (pub/reset-state!)))))

(deftest join-then-list
  (p/join! :room/r1 "cid-A" {:user 1})
  (p/join! :room/r1 "cid-B" {:user 2})
  (is (= {"cid-A" {:user 1}
          "cid-B" {:user 2}}
         (p/list-topic :room/r1)))
  (is (= 2 (p/count-topic :room/r1))))

(deftest leave-removes-from-roll
  (p/join! :room/r1 "cid-A" {})
  (p/leave! :room/r1 "cid-A")
  (is (= {} (p/list-topic :room/r1))))

(deftest leave-is-idempotent
  ;; Leaving an absent conn is a no-op; no broadcast.
  (let [seen (atom 0)]
    (pub/subscribe! :room/r1 (fn [_ _] (swap! seen inc)))
    (p/leave! :room/r1 "cid-NEVER")
    (is (zero? @seen))))

(deftest join-broadcasts-on-pubsub
  (let [seen (atom [])]
    (pub/subscribe! :room/r1 (fn [_t p] (swap! seen conj p)))
    (p/join! :room/r1 "cid-A" {:user 1})
    (is (= 1 (count @seen)))
    (let [evt (first @seen)]
      (is (= :join (:event evt)))
      (is (= "cid-A" (:conn-id evt)))
      (is (= {"cid-A" {:user 1}} (:roll evt))))))

(deftest leave-all-fans-out-to-every-topic
  (let [a (atom 0) b (atom 0)]
    (pub/subscribe! :room/r1 (fn [_ _] (swap! a inc)))
    (pub/subscribe! :room/r2 (fn [_ _] (swap! b inc)))
    (p/join! :room/r1 "cid-X" {})
    (p/join! :room/r2 "cid-X" {})
    (let [touched (p/leave-all! "cid-X")]
      (is (= #{:room/r1 :room/r2} touched))
      ;; One join broadcast + one leave broadcast on each topic.
      (is (= 2 @a))
      (is (= 2 @b)))))

(deftest topics-of-tracks-membership
  (p/join! :a "cid-1" {})
  (p/join! :b "cid-1" {})
  (p/join! :b "cid-2" {})
  (is (= #{:a :b} (p/topics-of "cid-1")))
  (is (= #{:b}    (p/topics-of "cid-2"))))

(deftest re-join-replaces-meta
  (p/join! :room/r1 "cid-A" {:user 1})
  (p/join! :room/r1 "cid-A" {:user 1 :status :away})
  (is (= {:user 1 :status :away}
         (get (p/list-topic :room/r1) "cid-A"))))
