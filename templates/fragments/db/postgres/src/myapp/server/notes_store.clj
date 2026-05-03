(ns myapp.server.notes-store
  (:require [next.jdbc.sql :as sql]
            [myapp.server.db :as db]))

(defn list-notes []
  (->> (db/query ["SELECT id, body, created_at FROM notes ORDER BY id DESC LIMIT 100"])
       (mapv (fn [row]
               {:id         (:notes/id row)
                :body       (:notes/body row)
                :created-at (str (:notes/created_at row))}))))

(defn add-note! [body]
  (sql/insert! (db/ds) :notes {:body body}))
