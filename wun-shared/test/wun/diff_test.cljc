(ns wun.diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.diff :as diff]))

(deftest diff-equal
  (is (= [] (diff/diff [:wun/Stack {} "x"] [:wun/Stack {} "x"]))))

(deftest diff-replaces-when-tag-changes
  (is (= [{:op :replace :path [] :value [:wun/Text {} "y"]}]
         (diff/diff [:wun/Stack {} "x"] [:wun/Text {} "y"]))))

(deftest diff-replaces-when-props-change
  (is (= [{:op :replace :path [] :value [:wun/Stack {:gap 8} "x"]}]
         (diff/diff [:wun/Stack {} "x"] [:wun/Stack {:gap 8} "x"]))))

(deftest diff-recurses-when-tag-and-props-match
  (is (= [{:op :replace :path [0] :value "b"}]
         (diff/diff [:wun/Stack {} "a"]
                    [:wun/Stack {} "b"]))))

(deftest diff-handles-no-props
  (is (= [{:op :replace :path [0] :value "b"}]
         (diff/diff [:wun/Stack "a"]
                    [:wun/Stack "b"]))))

(deftest diff-inserts-trailing-children
  (is (= [{:op :insert :path [2] :value "c"}]
         (diff/diff [:wun/Stack {} "a" "b"]
                    [:wun/Stack {} "a" "b" "c"]))))

(deftest diff-removes-trailing-children-high-to-low
  (is (= [{:op :remove :path [2]} {:op :remove :path [1]}]
         (diff/diff [:wun/Stack {} "a" "b" "c"]
                    [:wun/Stack {} "a"]))))

(deftest diff-deeply-nested
  (is (= [{:op :replace :path [0 0] :value "z"}]
         (diff/diff [:wun/Stack {} [:wun/Text {} "x"]]
                    [:wun/Stack {} [:wun/Text {} "z"]]))))

(deftest apply-replace-at-root
  (is (= [:wun/Text {} "x"]
         (diff/apply-patch [:wun/Stack {} "y"]
                           {:op :replace :path [] :value [:wun/Text {} "x"]}))))

(deftest apply-replace-deep
  (is (= [:wun/Stack {} [:wun/Text {} "z"]]
         (diff/apply-patch [:wun/Stack {} [:wun/Text {} "x"]]
                           {:op :replace :path [0 0] :value "z"}))))

(deftest apply-insert-and-remove
  (let [t [:wun/Stack {} "a" "b"]]
    (is (= [:wun/Stack {} "a" "b" "c"]
           (diff/apply-patch t {:op :insert :path [2] :value "c"})))
    (is (= [:wun/Stack {} "b"]
           (diff/apply-patch t {:op :remove :path [0]})))))

(deftest roundtrip-many-children
  (let [old [:wun/Stack {:gap 4}
             [:wun/Text {} "one"]
             [:wun/Text {} "two"]]
        new [:wun/Stack {:gap 4}
             [:wun/Text {} "uno"]
             [:wun/Text {} "two"]
             [:wun/Text {} "tres"]]
        patches (diff/diff old new)]
    (is (= new (diff/apply-patches old patches)))))
