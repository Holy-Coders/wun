(ns wun.server.session-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.session :as s]))

(use-fixtures :each
  (fn [t]
    (s/reset-state!)
    (try (t)
         (finally (s/reset-state!)))))

(deftest issue-token-is-non-empty-and-base64url
  (let [tok (s/issue-token)]
    (is (string? tok))
    (is (re-matches #"[A-Za-z0-9_-]+" tok))
    (is (>= (count tok) 40))))

(deftest issue-token-is-unique
  (let [tokens (repeatedly 50 s/issue-token)]
    (is (= 50 (count (set tokens))))))

(deftest revoked?-false-by-default
  (is (not (s/revoked? "tok-A"))))

(deftest revoke-and-revoked?
  (s/revoke! "tok-A" 0 60000)
  (is (s/revoked? "tok-A" 30000))
  ;; Expired by then.
  (is (not (s/revoked? "tok-A" 70000))))

(deftest rotate-issues-new-and-revokes-old
  (let [new-tok (s/rotate! "tok-OLD")]
    (is (string? new-tok))
    (is (not= "tok-OLD" new-tok))
    (is (s/revoked? "tok-OLD"))))

(deftest rotate-runs-handler
  (let [seen (atom nil)]
    (s/register-rotation-handler!
     (fn [old new] (reset! seen [old new])))
    (try
      (let [new-tok (s/rotate! "tok-OLD-2")]
        (is (= ["tok-OLD-2" new-tok] @seen)))
      (finally
        (s/register-rotation-handler! nil)))))

(deftest purge-drops-expired-entries
  (s/revoke! "tok-A" 0 1000)
  (s/revoke! "tok-B" 0 5000)
  (is (= 1 (s/purge-expired! 2000)))
  (is (not (s/revoked? "tok-A" 2000)))
  (is (s/revoked? "tok-B" 2000)))

(deftest set-store-allows-pluggable-backing
  (let [m (atom {})]
    (s/set-store!
     {:read   (fn [t]   (get @m t))
      :write  (fn [t e] (swap! m assoc t e))
      :delete (fn [t]   (swap! m dissoc t))})
    (s/revoke! "tok-Z" 0 60000)
    (is (contains? @m "tok-Z"))
    (is (s/revoked? "tok-Z" 30000))))
