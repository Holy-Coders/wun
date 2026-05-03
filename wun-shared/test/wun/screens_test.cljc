(ns wun.screens-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.screens :as screens]))

(use-fixtures :each
  (fn [t]
    (let [snap @screens/registry]
      (try (t)
           (finally (reset! screens/registry snap))))))

(deftest registers-and-looks-up-by-key
  (screens/defscreen ::main {:path "/" :render (fn [_] [:wun/Stack {}])})
  (is (some? (screens/lookup ::main))))

(deftest looks-up-by-path
  (screens/defscreen ::about {:path "/about" :render (fn [_] [:wun/Stack {}])})
  (is (= ::about (screens/lookup-by-path "/about")))
  (is (nil? (screens/lookup-by-path "/nope"))))

(deftest renders
  (screens/defscreen ::r {:path "/r" :render (fn [s] [:wun/Text {} (str (:n s))])})
  (is (= [:wun/Text {} "9"] (screens/render ::r {:n 9}))))

(deftest render-meta-when-defined
  (screens/defscreen ::m {:path "/m"
                          :render (fn [_] [:wun/Stack {}])
                          :meta   (fn [s] {:title (str "n=" (:n s))})})
  (is (= {:title "n=3"} (screens/render-meta ::m {:n 3}))))

(deftest render-meta-nil-when-absent
  (screens/defscreen ::nm {:path "/nm" :render (fn [_] [:wun/Stack {}])})
  (is (nil? (screens/render-meta ::nm {}))))

(deftest presentation-defaults-to-push
  (screens/defscreen ::push  {:path "/p" :render (fn [_] [:wun/Stack {}])})
  (screens/defscreen ::modal {:path "/m" :render (fn [_] [:wun/Stack {}]) :present :modal})
  (is (= :push  (screens/presentation ::push)))
  (is (= :modal (screens/presentation ::modal))))
