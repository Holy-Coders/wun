(ns wun.server.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.server.config :as cfg]))

(deftest parses-bools-leniently
  (is (true?  (cfg/parse-bool "true")))
  (is (true?  (cfg/parse-bool "TRUE")))
  (is (true?  (cfg/parse-bool "1")))
  (is (true?  (cfg/parse-bool "yes")))
  (is (true?  (cfg/parse-bool "on")))
  (is (false? (cfg/parse-bool "false")))
  (is (false? (cfg/parse-bool nil)))
  (is (false? (cfg/parse-bool ""))))

(deftest parses-ints
  (is (= 42 (cfg/parse-int "42")))
  (is (nil? (cfg/parse-int "nope")))
  (is (nil? (cfg/parse-int nil))))

(deftest parses-edn
  (is (= [:a :b] (cfg/parse-edn "[:a :b]")))
  (is (= {:n 5}  (cfg/parse-edn "{:n 5}")))
  (is (nil? (cfg/parse-edn nil))))

(deftest defaults-when-not-set
  (let [out (cfg/resolve {:foo {:default "fallback"}})]
    (is (= "fallback" (:foo out)))))

(deftest required-missing-throws
  (is (thrown-with-msg? Exception #"missing required"
                        (cfg/resolve {:must-have {:required? true}}))))

(deftest custom-env-key
  ;; The fallback name is :foo -> FOO, but :env override picks
  ;; FOO_OVERRIDE. Neither is set, so :default applies.
  (let [out (cfg/resolve {:foo {:env "FOO_OVERRIDE" :default "x"}})]
    (is (= "x" (:foo out)))))

(deftest resolve-many-merges-in-order
  (let [out (cfg/resolve-many
             {:a {:default 1}}
             {:b {:default 2}}
             {:a {:default 99}})]
    (is (= 99 (:a out)))
    (is (= 2  (:b out)))))
