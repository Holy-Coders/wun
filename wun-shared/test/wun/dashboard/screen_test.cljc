(ns wun.dashboard.screen-test
  (:require [clojure.test          :refer [deftest is testing]]
            [wun.dashboard.screen  :as dash]
            [wun.screens           :as screens]))

(def ^:private snap
  {:uptime-ms      125000
   :active-conns   2
   :registries     {:components 23 :screens 4 :intents 12}
   :connections    [{:conn-id "abc" :screen :counter/main :caps {} :fmt :transit}
                    {:conn-id "def" :screen :wun.dashboard/main :caps {} :fmt :json}]
   :intent-metrics [{:intent :counter/inc :count 7 :errors 0 :mean-ms 1.25}
                    {:intent :counter/dec :count 3 :errors 1 :mean-ms 2.5}]
   :recent-events  [{:t 1 :key :wun/connect    :attrs {:conn-id "abc"}}
                    {:t 2 :key :wun/intent.applied
                          :attrs {:conn-id "abc" :intent :counter/inc :duration-ms 1}}]})

(deftest renders-empty-state-without-snapshot
  (let [tree (dash/render {})]
    (is (vector? tree))
    (is (= :wun/Stack (first tree)))
    ;; Empty state mentions install! to give the operator a hint.
    (is (re-find #"install!" (pr-str tree)))))

(deftest renders-full-snapshot-tree
  (let [tree (dash/render {:wun.dashboard/snapshot snap})
        s    (pr-str tree)]
    (is (vector? tree))
    (is (= :wun/Stack (first tree)))
    (testing "header stats are present"
      (is (re-find #"Active connections" s))
      (is (re-find #"Wun dashboard"      s)))
    (testing "intent metrics rendered"
      (is (re-find #":counter/inc" s))
      (is (re-find #":counter/dec" s)))
    (testing "events rendered"
      (is (re-find #":wun/connect"        s))
      (is (re-find #":wun/intent.applied" s)))))

(deftest screen-is-registered-with-expected-path
  (let [spec (screens/lookup :wun.dashboard/main)]
    (is (= "/_wun/dashboard" (:path spec)))
    (is (= :push             (:present spec)))
    (is (fn?                 (:render spec)))
    (is (= "Wun dashboard"   (:title ((:meta spec) {}))))))
