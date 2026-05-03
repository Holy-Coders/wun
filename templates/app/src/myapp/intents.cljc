(ns myapp.intents
  "App-namespace intent registry. Each defintent registers a morph that
   runs identically on server (authoritative) and client (optimistic
   prediction)."
  (:require [wun.intents :refer [defintent]]))

(defintent :myapp/inc
  {:params [:map]
   :morph  (fn [state _params]
             (update state :counter (fnil inc 0)))})

(defintent :myapp/reset
  {:params [:map]
   :morph  (fn [_state _params] {:counter 0})})
