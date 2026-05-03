(ns wun.uploads-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.uploads :as up]))

(deftest empty-upload-shape
  (let [u (up/empty-upload "u-1" {:form :my :field :a :filename "x.png" :size 1000})]
    (is (= "u-1" (:upload-id u)))
    (is (= :queued (:status u)))
    (is (zero? (:received u)))
    (is (= 1000 (:size u)))))

(deftest start-sets-uploading
  (let [s (up/start {} "u-1")]
    (is (= :uploading (up/status s "u-1")))))

(deftest progress-monotonic
  (let [s (-> {}
              (up/start "u-1")
              (up/progress "u-1" 100)
              (up/progress "u-1" 75)
              (up/progress "u-1" 200))]
    (is (= 200 (up/received s "u-1")))))

(deftest complete-sets-status-and-url
  (let [s (-> {}
              (up/start "u-1")
              (up/complete "u-1" "/uploads/u-1.png"))]
    (is (up/complete? s "u-1"))
    (is (= "/uploads/u-1.png" (get-in s [:uploads "u-1" :url])))))

(deftest errored-sets-status-and-reason
  (let [s (-> {}
              (up/start "u-1")
              (up/errored "u-1" :too-large))]
    (is (up/errored? s "u-1"))
    (is (= :too-large (get-in s [:uploads "u-1" :error])))))

(deftest dissoc-removes-entry
  (let [s (-> {}
              (up/start "u-1")
              (up/dissoc-upload "u-1"))]
    (is (nil? (up/upload s "u-1")))))

(deftest percent-computed-when-size-known
  (let [s (-> {}
              (up/assoc-upload "u-1" (up/empty-upload "u-1" {:size 1000}))
              (up/progress "u-1" 250))]
    (is (= 25.0 (up/percent s "u-1")))))

(deftest percent-nil-without-size
  (let [s (-> {}
              (up/start "u-1")
              (up/progress "u-1" 100))]
    (is (nil? (up/percent s "u-1")))))

(deftest safe-filename-strips-paths
  (is (= "evil.png" (up/safe-filename "../../../etc/passwd/evil.png")))
  (is (= "x.txt"    (up/safe-filename "x.txt")))
  (is (= "upload.bin" (up/safe-filename nil)))
  (is (= "upload.bin" (up/safe-filename "    ")))
  (is (not (re-find #"\.\." (up/safe-filename "..hidden.")))))
