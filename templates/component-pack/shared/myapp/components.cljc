(ns myapp.components
  "User-namespace component pack template. Each defcomponent registers
   the component spec into the shared open registry; the runtime treats
   `:myapp/*` and `:wun/*` identically."
  (:require [wun.components :refer [defcomponent]]))

(defcomponent :myapp/MyComponent
  {:since    1
   :schema   [:map
              [:label {:optional true} :string]]
   :loading  :inherit
   :fallback :web
   :ios      "MyAppMyComponent"
   :android  "MyAppMyComponent"})
