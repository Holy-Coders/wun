(ns wun.capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [wun.capabilities :as cap]
            [wun.components :as components]))

(deftest parse-roundtrip
  (let [s "wun/Stack@1,wun/Text@2,wun/WebFrame@1"]
    (is (= s (cap/serialize (cap/parse s))))))

(deftest parse-empty-and-malformed
  (is (= {} (cap/parse nil)))
  (is (= {} (cap/parse "")))
  (is (= {:wun/Stack 1} (cap/parse "garbage,wun/Stack@1,more@oops"))))

(deftest substitute-keeps-supported
  (let [tree [:wun/Stack {:gap 8} [:wun/Text {} "x"]]
        caps {:wun/Stack 1 :wun/Text 1}]
    (is (= tree (cap/substitute tree caps)))))

(deftest substitute-replaces-smallest-unsupported-subtree
  (let [tree [:wun/Stack {:gap 8}
              [:wun/Text {} "header"]
              [:myapp/Greeting {:name "Aaron"}]]
        caps {:wun/Stack 1 :wun/Text 1 :wun/WebFrame 1}]
    (is (= [:wun/Stack {:gap 8}
            [:wun/Text {} "header"]
            [:wun/WebFrame {:missing :myapp/Greeting}]]
           (cap/substitute tree caps)))))

(deftest substitute-uses-src-builder
  (let [tree [:myapp/Greeting {:name "x"}]
        seen (atom nil)
        builder (fn [tag t]
                  (reset! seen [tag t])
                  "/web-frames/myapp/Greeting/abc")
        out (cap/substitute tree {} builder)]
    (is (= [:myapp/Greeting [:myapp/Greeting {:name "x"}]] @seen))
    (is (= [:wun/WebFrame {:missing :myapp/Greeting
                           :src     "/web-frames/myapp/Greeting/abc"}]
           out))))

(deftest substitute-respects-since
  (components/defcomponent ::v3only
    {:since 3 :schema [:map] :fallback :web})
  (try
    (let [tree [::v3only {}]]
      ;; Client only at v1 -> substitute
      (is (= [:wun/WebFrame {:missing ::v3only}]
             (cap/substitute tree {::v3only 1})))
      ;; Client at v3 -> keep
      (is (= tree (cap/substitute tree {::v3only 3}))))
    (finally
      (swap! components/registry dissoc ::v3only))))
