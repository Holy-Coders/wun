(ns wun.capabilities-property-test
  "Properties for capability negotiation:
     - The substituted tree never contains a tag the caps map says
       is unsupported (other than :wun/WebFrame, the universal
       fallback).
     - Substitution is idempotent: substituting a substituted tree
       returns the same tree.

   Note on monotonicity: it's tempting to claim 'richer caps = fewer
   WebFrames', but that's not a real invariant of `substitute`. The
   smallest-containing-subtree rule means richer caps can push
   substitution deeper into the tree, and a single outer WebFrame
   can fan out into multiple inner ones. The above two properties
   are the ones that DO hold."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [wun.capabilities :as cap]))

(def known-tags
  "Sample of the registered components -- the caps map keys are
   chosen from this set so the generator doesn't only produce
   uniformly-unsupported trees."
  [:wun/Stack :wun/Text :wun/Button :wun/Card :wun/Heading
   :wun/Image :wun/WebFrame :myapp/Greeting])

(def gen-tag (gen/elements known-tags))

(defn gen-tree [depth]
  (if (zero? depth)
    (gen/one-of [gen/string-alphanumeric
                 (gen/fmap (fn [t] [t {} "leaf"]) gen-tag)])
    (gen/let [tag      gen-tag
              children (gen/vector (gen-tree (dec depth)) 0 3)]
      (into [tag {}] children))))

(def gen-caps
  ;; Always include WebFrame (the substitution target). Otherwise
  ;; pick a random subset of the known tags at version 1.
  (gen/fmap (fn [tags] (into {:wun/WebFrame 1}
                             (map (fn [t] [t 1])) tags))
            (gen/vector-distinct gen-tag {:max-elements 5})))

(defn- tags-of [tree]
  (cond
    (string? tree) #{}
    (and (vector? tree) (keyword? (first tree)))
    (let [children (if (and (>= (count tree) 2) (map? (second tree)))
                     (drop 2 tree)
                     (rest tree))]
      (apply clojure.set/union #{(first tree)}
             (map tags-of children)))
    (vector? tree) (apply clojure.set/union (map tags-of tree))
    :else #{}))

(defspec substitution-eliminates-unsupported 100
  (prop/for-all [tree (gen-tree 3)
                 caps gen-caps]
    (let [out (cap/substitute tree caps)
          remaining (clojure.set/difference (tags-of out) (set (keys caps)))]
      ;; Anything left after substitution must already be supported,
      ;; or it's WebFrame (always present in caps by construction).
      (empty? remaining))))

(defspec substitution-is-idempotent 100
  (prop/for-all [tree (gen-tree 3)
                 caps gen-caps]
    (let [once  (cap/substitute tree caps)
          twice (cap/substitute once caps)]
      (= once twice))))

(defspec full-caps-leaves-tree-unchanged 100
  ;; If every tag the tree uses is in caps, substitute is identity.
  (prop/for-all [tree (gen-tree 3)]
    (let [caps (into {:wun/WebFrame 1}
                     (map (fn [t] [t 1])) (tags-of tree))]
      (= tree (cap/substitute tree caps)))))
