(ns wun.app.about
  "A second screen at `/about` demonstrating defscreen :path routing.
   Pulls the same counter state the main screen uses so we can see
   broadcasts hit a connection on the about screen too."
  (:require [wun.screens :refer [defscreen]]))

(defscreen :app/about
  {:path "/about"
   :render
   (fn [state]
     [:wun/Stack {:gap 12 :padding 24}
      [:wun/Text {:variant :h1} "About"]
      [:wun/Text {:variant :body}
       (str "Reached via :wun/navigate. The shared counter is at "
            (:counter state 0)
            "; tag changes from this screen still broadcast to every "
            "open connection because counter intents mutate global "
            "app state, while the navigation that pushed you here is "
            "scoped to your connection's screen-stack.")]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]
       [:wun/Button {:on-press {:intent :counter/reset :params {}}} "reset"]
       [:wun/Button {:on-press {:intent :wun/pop :params {}}} "← Back"]]])})
