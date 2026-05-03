(ns wun.theme-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.theme :as theme]))

(use-fixtures :each
  (fn [t]
    (let [snap (theme/default-theme)]
      (try (theme/set-default! {})
           (t)
           (finally (theme/set-default! snap))))))

(def example
  {:wun.color/primary "#0a66c2"
   :wun.spacing/md    16
   :wun.spacing/lg    24})

(deftest token-pred
  (is (theme/token? :wun.color/primary))
  (is (not (theme/token? :row)))
  (is (not (theme/token? "x")))
  (is (not (theme/token? 5))))

(deftest resolves-known-tokens
  (is (= "#0a66c2" (theme/resolve-value example :wun.color/primary)))
  (is (= 16 (theme/resolve-value example :wun.spacing/md))))

(deftest passes-through-non-tokens
  (is (= :row (theme/resolve-value example :row)))
  (is (= "literal" (theme/resolve-value example "literal")))
  (is (= 12 (theme/resolve-value example 12))))

(deftest leaves-unknown-tokens-in-place
  (is (= :wun.color/missing (theme/resolve-value example :wun.color/missing))))

(deftest resolves-tree-recursively
  (let [tree [:wun/Stack {:gap :wun.spacing/md :padding :wun.spacing/lg}
              [:wun/Text {:variant :h1 :color :wun.color/primary} "hi"]]
        out  (theme/resolve-tree example tree)]
    (is (= [:wun/Stack {:gap 16 :padding 24}
            [:wun/Text {:variant :h1 :color "#0a66c2"} "hi"]]
           out))))

(deftest resolve-tree-handles-missing-props
  (is (= [:wun/Stack [:wun/Text {} "hi"]]
         (theme/resolve-tree example
                             [:wun/Stack [:wun/Text {} "hi"]]))))

(deftest cascade-prefers-overrides
  (theme/set-default! {:wun.color/primary "#aaa"
                       :wun.color/text    "#000"})
  (let [eff (theme/cascade {:theme/overrides {:wun.color/primary "#bbb"}})]
    (is (= "#bbb" (:wun.color/primary eff)))
    (is (= "#000" (:wun.color/text    eff)))))

(deftest cascade-respects-screen-override
  (theme/set-default! {:wun.color/primary "#aaa"})
  (let [eff (theme/cascade {} {:wun.color/primary "#ccc"})]
    (is (= "#ccc" (:wun.color/primary eff)))))

(deftest conn-override-beats-screen
  (theme/set-default! {:wun.color/primary "#aaa"})
  (let [eff (theme/cascade {:theme/overrides {:wun.color/primary "#bbb"}}
                           {:wun.color/primary "#ccc"})]
    (is (= "#bbb" (:wun.color/primary eff)))))
