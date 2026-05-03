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

;; ---------------------------------------------------------------------------
;; Wire v2: key-aware children

(deftest keyed-equal-trees-emit-no-patches
  (let [t [:wun/Stack {}
           [:wun/Text {:key "a"} "A"]
           [:wun/Text {:key "b"} "B"]]]
    (is (= [] (diff/diff t t)))))

(deftest keyed-reorder-emits-children-op
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "b"} "B"]
             [:wun/Text {:key "c"} "C"]]
        new [:wun/Stack {}
             [:wun/Text {:key "c"} "C"]
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "b"} "B"]]
        patches (diff/diff old new)]
    (is (= 1 (count patches)))
    (let [p (first patches)]
      (is (= :children (:op p)))
      (is (= [] (:path p)))
      (is (= [{:key "c" :existing? true}
              {:key "a" :existing? true}
              {:key "b" :existing? true}]
             (:order p))))
    (is (= new (diff/apply-patches old patches)))))

(deftest keyed-insert-emits-inline-value
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]]
        new [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "b"} "B"]]
        patches (diff/diff old new)]
    (is (= 1 (count patches)))
    (let [p (first patches)]
      (is (= :children (:op p)))
      (is (= [{:key "a" :existing? true}
              {:key "b" :existing? false :value [:wun/Text {:key "b"} "B"]}]
             (:order p))))
    (is (= new (diff/apply-patches old patches)))))

(deftest keyed-remove-elides-from-order
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "b"} "B"]
             [:wun/Text {:key "c"} "C"]]
        new [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "c"} "C"]]
        patches (diff/diff old new)]
    (is (= 1 (count patches)))
    (is (= [{:key "a" :existing? true}
            {:key "c" :existing? true}]
           (:order (first patches))))
    (is (= new (diff/apply-patches old patches)))))

(deftest keyed-prop-change-recurses-no-children-op
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {:key "b"} "B"]]
        new [:wun/Stack {}
             [:wun/Text {:key "a"} "A!"]
             [:wun/Text {:key "b"} "B"]]
        patches (diff/diff old new)]
    (is (= 1 (count patches)))
    (let [p (first patches)]
      ;; Order unchanged -> no :children op, just a recursive replace.
      (is (= :replace (:op p)))
      (is (= [0 0] (:path p))))
    (is (= new (diff/apply-patches old patches)))))

(deftest keyed-reorder-with-prop-change
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A1"]
             [:wun/Text {:key "b"} "B"]]
        new [:wun/Stack {}
             [:wun/Text {:key "b"} "B"]
             [:wun/Text {:key "a"} "A2"]]
        patches (diff/diff old new)]
    ;; Topology op + recursive replace at new index of "a" (1).
    (is (= new (diff/apply-patches old patches)))))

(deftest mixed-keyed-and-unkeyed-falls-back-to-positional
  (let [old [:wun/Stack {}
             [:wun/Text {:key "a"} "A"]
             [:wun/Text {} "no-key"]]
        new [:wun/Stack {}
             [:wun/Text {:key "a"} "A!"]
             [:wun/Text {} "no-key"]]
        patches (diff/diff old new)]
    (is (every? #{:replace :insert :remove} (map :op patches)))
    (is (= new (diff/apply-patches old patches)))))

(deftest big-keyed-reorder-roundtrips
  (let [build (fn [keys-vec props-fn]
                (into [:wun/Stack {}]
                      (map (fn [k]
                             [:wun/Card (merge {:key k} (props-fn k))
                              [:wun/Text {} (str "card-" k)]])
                           keys-vec)))
        old (build ["a" "b" "c" "d" "e" "f"] (constantly {}))
        new (build ["f" "a" "x" "c" "b"] (fn [k] (when (= k "a") {:badge true})))
        patches (diff/diff old new)]
    (is (= new (diff/apply-patches old patches)))))
