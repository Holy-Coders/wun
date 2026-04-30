(ns myapp.components
  "User-namespace component definitions. Outside the `wun.*` tree on
   purpose: there's no privileged path between framework code and
   user code -- both register through the same `defcomponent` API.

   In a real product these would live in their own Clojure library
   that wun-server (or a downstream service) depends on; for the
   spike they sit under wun-shared so the build's simple."
  (:require [wun.components :refer [defcomponent]]))

(defcomponent :myapp/Greeting
  {:since    1
   :schema   [:map
              [:name {:optional true} :string]]
   :loading  :inherit
   :fallback :web
   :ios      "MyAppGreeting"
   :android  "MyAppGreeting"})
