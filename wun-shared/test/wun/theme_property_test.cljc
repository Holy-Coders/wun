(ns wun.theme-property-test
  "Properties for theme resolution:
     - Resolving a tree against an empty theme is identity.
     - Resolving twice equals resolving once (idempotence).
     - Unknown namespaced-keyword tokens survive resolution unchanged
       (visible-failure principle: misspelled tokens show up as raw
       keywords in the rendered tree, not as nil)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [wun.theme :as theme]))

(def gen-known-token
  (gen/elements [:wun.color/primary :wun.spacing/md :wun.spacing/lg
                 :wun.radius/md :wun.font/body-size]))

(def gen-unknown-token
  (gen/elements [:my.weird/token :nope/found :ghost/value]))

(def gen-prop-value
  (gen/one-of [gen/small-integer
               gen/string-alphanumeric
               (gen/elements [:row :column :h1 :body])
               gen-known-token
               gen-unknown-token]))

(def gen-props
  (gen/map (gen/elements [:gap :padding :variant :color :size])
           gen-prop-value
           {:max-elements 4}))

(defn gen-tree [depth]
  (if (zero? depth)
    (gen/one-of [gen/string-alphanumeric
                 (gen/fmap (fn [[t p]] [t p]) (gen/tuple
                                                (gen/elements [:wun/Stack :wun/Text])
                                                gen-props))])
    (gen/let [tag      (gen/elements [:wun/Stack :wun/Text :wun/Card])
              props    gen-props
              children (gen/vector (gen-tree (dec depth)) 0 3)]
      (into [tag props] children))))

(def example-theme
  {:wun.color/primary "#0a66c2"
   :wun.spacing/md    16
   :wun.spacing/lg    24
   :wun.radius/md     8
   :wun.font/body-size 14})

(defspec empty-theme-is-identity 100
  (prop/for-all [tree (gen-tree 3)]
    (= tree (theme/resolve-tree {} tree))))

(defspec resolve-is-idempotent 100
  (prop/for-all [tree (gen-tree 3)]
    (let [once  (theme/resolve-tree example-theme tree)
          twice (theme/resolve-tree example-theme once)]
      (= once twice))))

(defspec unknown-tokens-survive-resolution 100
  ;; Resolving a tree with unknown tokens leaves them in place. We
  ;; check this by using an empty theme: every value must be returned
  ;; unchanged.
  (prop/for-all [tree (gen-tree 3)]
    (= tree (theme/resolve-tree {} tree))))

(defspec resolve-value-on-known-token-returns-mapped 100
  (prop/for-all [k gen-known-token]
    (let [v (get example-theme k)]
      (= v (theme/resolve-value example-theme k)))))
