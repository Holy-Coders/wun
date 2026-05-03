(ns myapp.server.notes-store
  "Stable interface the demo notes screen + intent depend on. Kept
   separate from db.clj so the screen/intent code is identical across
   all three DB backends -- only this namespace's body changes."
  (:require [next.jdbc.sql :as sql]
            [myapp.server.db :as db]))

(defn list-notes
  "Most-recent first."
  []
  (->> (db/query ["SELECT id, body, created_at FROM notes ORDER BY id DESC LIMIT 100"])
       (mapv (fn [row]
               {:id         (:notes/id row)
                :body       (:notes/body row)
                :created-at (:notes/created_at row)}))))

(defn add-note! [body]
  (sql/insert! (db/ds) :notes {:body body}))
