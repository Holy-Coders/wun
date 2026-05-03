(ns wun.diff-property-test
  "Property-based tests for the diff/apply round-trip. The single
   property that matters most: for any pair of Hiccup trees `(old, new)`,
   applying the diff to `old` reproduces `new` exactly. If this ever
   fails, every cross-platform consumer of the wire is at risk of
   silent desync.

   Generators build small Hiccup trees with a bounded depth and a
   handful of component tags / props. The tree shape mirrors what
   real screens emit: nested `[:wun/Stack {} ...]`, leaf
   `[:wun/Text {:variant ...}]` with string children, occasional
   keyed children that exercise the wire-v2 :children op."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [wun.diff :as diff]))

;; ---------------------------------------------------------------------------
;; Generators

(def gen-tag
  (gen/elements [:wun/Stack :wun/Text :wun/Button :wun/Card :wun/Heading]))

(def gen-prop-key
  (gen/elements [:gap :padding :variant :level :tone :direction :on-press
                 :title :class :alt]))

(def gen-prop-value
  (gen/one-of [gen/small-integer
               gen/string-alphanumeric
               (gen/elements [:row :column :h1 :h2 :body :info :primary])
               (gen/return nil)]))

(def gen-props
  (gen/fmap (fn [pairs] (into {} pairs))
            (gen/vector (gen/tuple gen-prop-key gen-prop-value) 0 4)))

(def gen-keyed-props
  ;; Props that always include a :key. Children built with this generator
  ;; activate the wire-v2 keyed-children path.
  (gen/fmap (fn [[base k]]
              (assoc base :key k))
            (gen/tuple gen-props gen/string-alphanumeric)))

(defn gen-tree
  "Bounded recursive Hiccup generator. Depth is capped to keep test
   shrinking tractable; a real-world screen is rarely more than
   eight nodes deep anyway."
  [depth]
  (if (zero? depth)
    (gen/one-of
     [gen/string-alphanumeric
      (gen/fmap (fn [[t p s]] [t p s])
                (gen/tuple gen-tag gen-props gen/string-alphanumeric))])
    (let [child  (gen-tree (dec depth))
          ;; Either positional children (any props) or all-keyed
          ;; children (every child has a :key) -- never mixed at one
          ;; level, since the differ falls back to position when even
          ;; one child lacks a :key.
          keyed?-and-props (gen/one-of
                            [(gen/tuple (gen/return false) gen-props)
                             (gen/tuple (gen/return true)  gen-keyed-props)])]
      (gen/let [tag                gen-tag
                [keyed? props]     keyed?-and-props
                children           (gen/vector
                                    (if keyed?
                                      (gen/let [t  gen-tag
                                                p  gen-keyed-props
                                                ks gen/string-alphanumeric]
                                        [t p ks])
                                      child)
                                    0 4)]
        (into [tag props] children)))))

;; ---------------------------------------------------------------------------
;; Properties

(defspec diff-then-apply-roundtrips 200
  (prop/for-all [old (gen-tree 3)
                 new (gen-tree 3)]
    (let [patches (diff/diff old new)]
      (= new (diff/apply-patches old patches)))))

(defspec diff-equal-trees-emits-no-patches 100
  (prop/for-all [t (gen-tree 3)]
    (= [] (diff/diff t t))))

(defspec apply-empty-patch-list-is-identity 100
  (prop/for-all [t (gen-tree 3)]
    (= t (diff/apply-patches t []))))

(defspec v1-diff-also-roundtrips 200
  ;; The v1 fall-back differ (no :children op) must also produce a
  ;; valid edit script so older clients negotiated to v1 stay correct.
  (prop/for-all [old (gen-tree 3)
                 new (gen-tree 3)]
    (let [patches (diff/diff-v1 old new)]
      (and (every? #{:replace :insert :remove} (map :op patches))
           (= new (diff/apply-patches old patches))))))
