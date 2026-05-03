(ns wun.web.renderers-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [wun.web.renderers :as r]))

(use-fixtures :each
  (fn [t]
    (let [snap @r/registry]
      (try (t)
           (finally (reset! r/registry snap))))))

(deftest passes-through-strings-and-numbers
  (is (= "x" (r/render-node "x")))
  (is (= "5" (r/render-node 5)))
  (is (nil? (r/render-node nil)))
  (is (= "true" (r/render-node true))))

(deftest dispatches-keyword-tags-through-registry
  (r/register! ::greet
               (fn [props children]
                 (into [:div.greet {:data-name (:name props)}] children)))
  (is (= [:div.greet {:data-name "Aaron"} "hi"]
         (r/render-node [::greet {:name "Aaron"} "hi"]))))

(deftest unknown-component-falls-back-to-placeholder
  (let [out (r/render-node [::not-registered {} "x"])]
    (is (= :div.wun-unknown (first out)))
    (is (re-find #"not-registered" (last out)))))

(deftest plain-vectors-without-keyword-tag-flatten
  (is (= ["a" "b"] (r/render-node '("a" "b")))))

(deftest renders-nested-trees
  (r/register! ::stack
               (fn [_ children] (into [:div.stack] children)))
  (r/register! ::leaf
               (fn [props _] [:span (str (:value props))]))
  (is (= [:div.stack [:span "1"] [:span "2"]]
         (r/render-node [::stack {} [::leaf {:value 1}] [::leaf {:value 2}]]))))
