(ns myapp.components
  "App-namespace component declarations. Mirror the platform-specific
   renderers in src/myapp/web/main.cljs and (eventually) ios/Sources/
   + android/src/main/kotlin/myapp/."
  (:require [wun.components :refer [defcomponent]]))

;; Drop your `defcomponent` calls here. Example -- `:myapp/Card`
;; takes an optional `:title` and renders a styled wrapper around its
;; children:
;;
;; (defcomponent :myapp/Card
;;   {:since    1
;;    :schema   [:map [:title {:optional true} :string]]
;;    :fallback :web
;;    :ios      "Card"
;;    :android  "Card"})
