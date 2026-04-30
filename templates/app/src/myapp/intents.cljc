(ns myapp.intents
  "App-namespace intent registry. Each definent registers a morph that
   runs identically on server (authoritative) and client (optimistic
   prediction)."
  (:require [wun.intents :refer [definent]]))

(definent :myapp/inc
  {:params [:map]
   :morph  (fn [state _params]
             (update state :counter (fnil inc 0)))})

(definent :myapp/reset
  {:params [:map]
   :morph  (fn [_state _params] {:counter 0})})
