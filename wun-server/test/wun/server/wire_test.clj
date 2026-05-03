(ns wun.server.wire-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.server.wire :as wire]))

(deftest transit-roundtrip
  (let [v {:patches [{:op :replace :path [] :value [:wun/Text {} "x"]}]
           :status :ok}]
    (is (= v (wire/read-transit-json (wire/write-transit-json v))))))

(deftest json-encodes-keywords-as-namespaced-strings
  (let [s (wire/write-json {:patches [{:op :replace :path [] :value [:wun/Text {} "x"]}]
                            :status :ok})
        v (wire/read-json s)]
    (is (= "ok" (:status v)))
    (is (= "replace" (-> v :patches first :op)))
    (is (= "wun/Text" (-> v :patches first :value first)))))

(deftest json-encodes-uuids-as-strings
  (let [u (java.util.UUID/randomUUID)
        s (wire/write-json {:resolves-intent u})
        v (wire/read-json s)]
    (is (= (str u) (:resolves-intent v)))))

(deftest patch-envelope-shape
  (let [env (wire/patch-envelope [{:op :replace :path [] :value :v}])]
    (is (= :ok (:status env)))
    (is (= wire/envelope-version (:envelope-version env)))
    (is (= 1 (count (:patches env))))))

(deftest patch-envelope-extras-passthrough
  (let [env (wire/patch-envelope []
                                 {:resolves-intent "id-1"
                                  :state {:n 1}
                                  :conn-id "cid-1"
                                  :screen-stack [:counter/main]
                                  :presentations [:push]
                                  :meta {:title "x"}
                                  :csrf-token "tok-X"
                                  :resync? true
                                  :theme {:wun.color/primary "#0a66c2"}
                                  :ignored-key "foo"})]
    (is (= "id-1" (:resolves-intent env)))
    (is (= {:n 1} (:state env)))
    (is (= "cid-1" (:conn-id env)))
    (is (= [:counter/main] (:screen-stack env)))
    (is (= [:push] (:presentations env)))
    (is (= {:title "x"} (:meta env)))
    (is (= "tok-X" (:csrf-token env)))
    (is (true? (:resync? env)))
    (is (= {:wun.color/primary "#0a66c2"} (:theme env)))
    ;; Unknown keys are stripped to keep the wire surface tight.
    (is (not (contains? env :ignored-key)))))

(deftest encode-envelope-honours-fmt
  (let [env (wire/patch-envelope [])]
    (is (string? (wire/encode-envelope :json    env)))
    (is (string? (wire/encode-envelope :transit env)))
    ;; JSON output is human-readable; transit-json begins with [.
    (is (re-find #"\"status\"" (wire/encode-envelope :json env)))))
