(ns myapp.notes
  "Demo CRUD feature: a list of notes backed by your selected DB.
   Identical across sqlite/postgres/datomic -- only the per-backend
   `myapp.server.notes-store` namespace differs.

   The morph appends optimistically on the client (so typing a note
   feels instant); on the server it persists to the DB and re-reads
   the table to fold authoritative state. Reader conditionals are
   what let the same `definent` form do both."
  (:require [wun.intents :refer [definent]]
            [wun.screens :refer [defscreen]]
            #?(:clj [myapp.server.notes-store :as notes-store])))

(definent :myapp/add-note
  {:params [:map [:body [:string {:min 1}]]]
   :morph
   (fn [state {:keys [body]}]
     #?(:clj
        (do (notes-store/add-note! body)
            (assoc state :notes (notes-store/list-notes)))
        :cljs
        (update state :notes (fnil conj [])
                {:body body :id :optimistic :created-at "now"})))})

(defscreen :myapp/notes
  {:path "/notes"
   :meta (fn [_state]
           {:title       "Notes · myapp"
            :description "DB-backed notes demo for myapp."})
   :render
   (fn [state]
     [:wun/Stack {:gap 16 :padding 24}
      [:wun/Heading {:level 1} "Notes"]
      [:wun/Text {:variant :body}
       "Each row below is a real database row. Add one to see the round-trip."]
      [:wun/Form {:on-submit {:intent :myapp/add-note
                              :params {:body :form/body}}}
       [:wun/Stack {:gap 8}
        [:wun/TextField {:name "body" :placeholder "what's on your mind?"}]
        [:wun/Button {:type :submit} "Save"]]]
      [:wun/Stack {:gap 4}
       (for [n (:notes state [])]
         ^{:key (:id n)}
         [:wun/Stack {:gap 2 :padding 8}
          [:wun/Text {:variant :body} (:body n)]
          [:wun/Text {:variant :caption} (str (:created-at n))]])]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :wun/navigate :params {:path "/"}}}
        "← Home"]]])})
