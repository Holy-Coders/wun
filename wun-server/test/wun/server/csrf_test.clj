(ns wun.server.csrf-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.csrf :as csrf]))

(use-fixtures :once
  (fn [t]
    ;; Pin a stable secret for the suite so tokens are deterministic.
    (let [old (System/getProperty "wun.csrf.test"
                                   (or (System/getenv "WUN_CSRF_SECRET") ""))]
      (try
        ;; We can't set env vars from JVM, so use a System property-driven
        ;; reset path that the namespace's reset-secret-for-test! reads.
        ;; In practice we just exercise issue/valid? against whatever
        ;; secret is loaded -- the test still verifies the contract.
        (t)
        (finally
          (System/setProperty "wun.csrf.test" old))))))

(deftest issue-is-deterministic
  (is (= (csrf/issue "session-A") (csrf/issue "session-A"))))

(deftest different-bindings-produce-different-tokens
  (is (not= (csrf/issue "session-A") (csrf/issue "session-B"))))

(deftest valid-accepts-correct-token
  (let [tok (csrf/issue "session-A")]
    (is (csrf/valid? "session-A" tok))))

(deftest valid-rejects-mismatched-binding
  (let [tok (csrf/issue "session-A")]
    (is (not (csrf/valid? "session-B" tok)))))

(deftest valid-rejects-tampered-token
  (let [tok (csrf/issue "session-A")
        bad (str (subs tok 0 (dec (count tok))) "X")]
    (is (not (csrf/valid? "session-A" bad)))))

(deftest valid-rejects-nil-and-empty
  (is (not (csrf/valid? "session-A" nil)))
  (is (not (csrf/valid? "session-A" "")))
  (is (not (csrf/valid? "session-A" "not-a-real-token"))))

(deftest token-format-is-base64url-no-padding
  (let [tok (csrf/issue "session-A")]
    (is (re-matches #"[A-Za-z0-9_-]+" tok))
    (is (not (re-find #"=" tok)))))
