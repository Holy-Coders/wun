(ns wun.app.counter
  "The phase-0 counter app, expressed in user-namespace code that flows
   through the same registries the framework uses. Demonstrates that
   there is no privileged path between `:wun/*` and `:counter/*`."
  (:require [wun.intents :refer [definent]]
            [wun.screens :refer [defscreen]]))

;; ---------------------------------------------------------------------------
;; Intents -- server-authoritative state transitions. Morphs are pure
;; (state, params) -> state fns; phase 1.C will run these on the web
;; client too for optimistic prediction.

(definent :counter/inc
  {:params [:map]
   :morph  (fn [state _params] (update state :counter (fnil inc 0)))})

(definent :counter/dec
  {:params [:map]
   :morph  (fn [state _params] (update state :counter (fnil dec 0)))})

(definent :counter/reset
  {:params [:map]
   :morph  (fn [_state _params] {:counter 0})})

;; ---------------------------------------------------------------------------
;; Screen -- pure (state) -> Hiccup. Action handlers are data, not fns:
;; `{:intent :counter/inc :params {}}`.

(defscreen :counter/main
  {:path "/"
   :render
   (fn [state]
     [:wun/Stack {:gap 12 :padding 24}
      [:wun/Text {:variant :h1} (str "Counter: " (:counter state 0))]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :counter/dec   :params {}}} "-"]
       [:wun/Button {:on-press {:intent :counter/inc   :params {}}} "+"]
       [:wun/Button {:on-press {:intent :counter/reset :params {}}} "reset"]]
      [:wun/Text {:variant :body}
       (str "Phase 1.A: open registries. The foundational :wun/* vocabulary, "
            "this :counter/main screen, and the :counter/* intents are all "
            "registered through the same APIs. No privileged path.")]])})
