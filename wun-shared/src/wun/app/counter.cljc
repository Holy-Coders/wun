(ns wun.app.counter
  "The phase-0 counter app, expressed in user-namespace code that flows
   through the same registries the framework uses. Demonstrates that
   there is no privileged path between `:wun/*` and `:counter/*`."
  (:require [wun.intents :refer [definent]]
            [wun.screens :refer [defscreen]]))

;; ---------------------------------------------------------------------------
;; Intents -- server-authoritative state transitions. Morphs are pure
;; (state, params) -> state fns and run on both server and client (the
;; latter for optimistic prediction). `:params` is a Malli schema; the
;; framework rejects malformed payloads at both ends of the wire.

(definent :counter/inc
  {:params [:map]
   :morph  (fn [state _params] (update state :counter (fnil inc 0)))})

(definent :counter/dec
  {:params [:map]
   :morph  (fn [state _params] (update state :counter (fnil dec 0)))})

(definent :counter/reset
  {:params [:map]
   :morph  (fn [_state _params] {:counter 0})})

(definent :counter/by
  {:params [:map [:n :int]]
   :morph  (fn [state {:keys [n]}] (update state :counter (fnil + 0) n))})

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
       [:wun/Button {:on-press {:intent :counter/dec   :params {}}}     "-"]
       [:wun/Button {:on-press {:intent :counter/inc   :params {}}}     "+"]
       [:wun/Button {:on-press {:intent :counter/by    :params {:n 5}}} "+5"]
       [:wun/Button {:on-press {:intent :counter/reset :params {}}}     "reset"]]
      [:wun/Text {:variant :body}
       (str "Phase 1.D-Malli: intent params are now Malli-validated at both "
            "ends of the wire. :counter/by takes {:n :int}; the +5 button "
            "fires it. Manually POSTing :counter/by with a non-int :n is "
            "rejected with an error envelope.")]])})
