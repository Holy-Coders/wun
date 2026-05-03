(ns wun.errors-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.errors :as errors]))

(defn- snapshot-formatter [t]
  (errors/format-error t))

(deftest safe-render-passes-through-on-success
  (let [render (fn [s] [:wun/Stack {} (str (:n s))])
        out    (errors/safe-render render {:n 5} nil)]
    (is (= [:wun/Stack {} "5"] out))))

(deftest safe-render-returns-error-tree-on-throw
  (let [render (fn [_] (throw #?(:clj  (RuntimeException. "boom")
                                 :cljs (ex-info "boom" {}))))
        out    (errors/safe-render render {} nil)]
    (is (= :wun/ErrorBoundary (first out)))
    (let [props (second out)]
      (is (re-find #"boom" (:reason props))))))

(deftest safe-render-calls-on-throw
  (let [seen   (atom nil)
        render (fn [_] (throw #?(:clj  (RuntimeException. "x")
                                 :cljs (ex-info "x" {}))))
        out    (errors/safe-render render {} (fn [t] (reset! seen t)))]
    (is (= :wun/ErrorBoundary (first out)))
    (is (some? @seen))))

(deftest custom-formatter-is-honoured
  (let [orig @#'errors/error-formatter]
    (try
      (errors/set-error-formatter! (fn [_t] "REDACTED"))
      (let [render (fn [_] (throw #?(:clj  (RuntimeException. "leaky")
                                     :cljs (ex-info "leaky" {}))))
            out    (errors/safe-render render {} nil)]
        (is (= "REDACTED" (:reason (second out)))))
      (finally
        (reset! @#'errors/error-formatter @orig)))))
