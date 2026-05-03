(ns wun.server.rate-limit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.rate-limit :as rl]
            [wun.server.telemetry :as t]))

(use-fixtures :each
  (fn [test-fn]
    (let [snap @rl/config]
      (rl/reset-state!)
      (try (test-fn)
           (finally
             (reset! rl/config snap)
             (rl/reset-state!))))))

(deftest first-request-allowed
  (is (rl/allow? :conn "cid-A")))

(deftest blocks-when-bucket-empty
  (rl/configure! {:conn {:capacity 3 :refill-per-sec 1}})
  (rl/reset-state!)
  (let [now 1000]
    (is (rl/allow? :conn "cid-A" now))
    (is (rl/allow? :conn "cid-A" now))
    (is (rl/allow? :conn "cid-A" now))
    (is (not (rl/allow? :conn "cid-A" now)))))

(deftest refills-over-time
  (rl/configure! {:conn {:capacity 2 :refill-per-sec 1}})
  (rl/reset-state!)
  (is (rl/allow? :conn "cid-A" 0))   ;; tokens 2 -> 1
  (is (rl/allow? :conn "cid-A" 0))   ;; tokens 1 -> 0
  (is (not (rl/allow? :conn "cid-A" 0))) ;; rejected
  ;; 1s later: one token has refilled.
  (is (rl/allow? :conn "cid-A" 1000)) ;; tokens 0 + 1 -> 0
  (is (not (rl/allow? :conn "cid-A" 1000)))
  ;; 2s later still empty + 1 refilled in last second = 1 token; capacity is 2 so we cap there.
  (is (rl/allow? :conn "cid-A" 2000)) ;; tokens 0 + 1 -> 0
  (is (not (rl/allow? :conn "cid-A" 2000)))
  ;; Wait long enough to refill back to capacity.
  (is (rl/allow? :conn "cid-A" 4000)) ;; tokens 0 + 2 (capped) -> 1
  (is (rl/allow? :conn "cid-A" 4000)))

(deftest scopes-are-independent
  (rl/configure! {:conn {:capacity 1 :refill-per-sec 1}
                  :ip   {:capacity 1 :refill-per-sec 1}
                  :idle-evict-ms 60000})
  (rl/reset-state!)
  (is (rl/allow? :conn "cid-A" 0))
  ;; Conn bucket exhausted, but ip scope is untouched.
  (is (not (rl/allow? :conn "cid-A" 0)))
  (is (rl/allow? :ip "10.0.0.1" 0)))

(deftest unknown-scope-throws
  (is (thrown? Exception (rl/allow? :unknown "x"))))

(deftest emits-telemetry-on-block
  (let [seen (atom [])]
    (rl/configure! {:conn {:capacity 1 :refill-per-sec 1}})
    (rl/reset-state!)
    (t/with-sink (fn [k a] (when (= :wun/rate-limit.block k)
                             (swap! seen conj a)))
      (fn []
        (rl/allow? :conn "cid-A" 0)
        (rl/allow? :conn "cid-A" 0)
        (rl/allow? :conn "cid-A" 0)))
    (is (= 2 (count @seen)))
    (is (= :conn (-> @seen first :scope)))))

(deftest evict-idle-drops-old-buckets
  (rl/configure! {:conn {:capacity 5 :refill-per-sec 1} :idle-evict-ms 1000})
  (rl/reset-state!)
  (rl/allow? :conn "cid-A" 0)
  (rl/allow? :conn "cid-B" 5000)
  ;; Now cid-A is idle for 5s; cid-B is fresh.
  (is (= 1 (rl/evict-idle! 5000)))
  ;; cid-B still tracked, cid-A re-allocated fresh on next access.
  (is (rl/allow? :conn "cid-B" 5000)))
