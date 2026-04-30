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
       (str "Different screen reached via /wun?path=/about. "
            "The shared counter is at " (:counter state 0) ".")]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]
       [:wun/Button {:on-press {:intent :counter/reset :params {}}} "reset"]]])})
