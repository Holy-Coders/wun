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
   :meta
   (fn [state]
     {:title       (str "Counter " (:counter state 0) " · Wun")
      :description "Server-driven counter demo for Wun."
      :theme-color "#0a66c2"
      :og          {:title (str "Counter at " (:counter state 0))
                    :type  "website"}})
   :render
   (fn [state]
     [:wun/Stack {:gap 12 :padding 24}
      [:myapp/Greeting {:name "Aaron"}]
      [:wun/Text {:variant :h1} (str "Counter: " (:counter state 0))]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :counter/dec   :params {}}}     "-"]
       [:wun/Button {:on-press {:intent :counter/inc   :params {}}}     "+"]
       [:wun/Button {:on-press {:intent :counter/by    :params {:n 5}}} "+5"]
       [:wun/Button {:on-press {:intent :counter/reset :params {}}}     "reset"]]
      [:wun/Text {:variant :body}
       (str "Phase 2.H: :myapp/Greeting above is a user-namespace "
            "component shipped from the wun-ios-example Swift package. "
            "Clients that advertise :myapp/Greeting render it natively; "
            "clients that don't (like the current web bundle) see a "
            "WebFrame fallback in its place.")]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :wun/navigate
                                :params {:path "/about"}}}
        "→ About"]]])})
