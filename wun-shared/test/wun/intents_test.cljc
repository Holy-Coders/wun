(ns wun.intents-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.intents :as intents]))

(use-fixtures :each
  (fn [t]
    (let [snap @intents/registry]
      (try (t)
           (finally (reset! intents/registry snap))))))

(deftest registers-and-looks-up
  (intents/defintent ::inc {:params [:map] :morph (fn [s _] (update s :n inc))})
  (is (= ::inc (some #{::inc} (keys @intents/registry))))
  (is (fn? (:morph (intents/lookup ::inc)))))

(deftest validate-params-returns-nil-on-success
  (intents/defintent ::ok {:params [:map [:n :int]] :morph (fn [s _] s)})
  (is (nil? (intents/validate-params ::ok {:n 5}))))

(deftest validate-params-explains-on-failure
  (intents/defintent ::ok {:params [:map [:n :int]] :morph (fn [s _] s)})
  (let [err (intents/validate-params ::ok {:n "nope"})]
    (is (some? err))
    (is (= ::ok (:intent err)))
    (is (some? (:explanation err)))))

(deftest apply-intent-runs-morph
  (intents/defintent ::add {:params [:map [:n :int]]
                           :morph  (fn [s {:keys [n]}] (update s :total (fnil + 0) n))})
  (is (= {:total 7} (intents/apply-intent {} ::add {:n 7}))))

(deftest apply-intent-no-op-on-validation-failure
  (intents/defintent ::strict {:params [:map [:n :int]]
                              :morph  (fn [s _] (assoc s :ran? true))})
  (is (= {} (intents/apply-intent {} ::strict {:n "x"}))))

(deftest apply-intent-no-op-on-unknown-intent
  (is (= {:keep :me} (intents/apply-intent {:keep :me} ::nonexistent {}))))

(deftest server-only-flag
  (intents/defintent ::auth {:server-only? true :params [:map] :morph (fn [s _] s)})
  (intents/defintent ::not-auth {:params [:map] :morph (fn [s _] s)})
  (is (intents/server-only? ::auth))
  (is (not (intents/server-only? ::not-auth)))
  (is (not (intents/server-only? ::nonexistent))))
