(ns myapp.screens
  "App screens. Each defscreen carries a `:path` (matched on SSE
   connect or via :wun/navigate {:path \"/foo\"}) and a `:render` fn
   that produces a Hiccup-shaped tree using namespaced component
   keywords."
  (:require [wun.screens :refer [defscreen]]))

(defscreen :myapp/home
  {:path "/"
   :render
   (fn [state]
     [:wun/Stack {:gap 12 :padding 24}
      [:wun/Heading {:level 1} "myapp"]
      [:wun/Text    {:variant :body}
                    (str "Counter is at " (:counter state 0) ".")]
      [:wun/Stack   {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :myapp/inc   :params {}}} "+"]
       [:wun/Button {:on-press {:intent :myapp/reset :params {}}} "reset"]]])})
