(ns myapp.server.notes-store
  (:require [datomic.client.api :as d]
            [myapp.server.db    :as db]))

(defn list-notes []
  (->> (d/q '[:find ?e ?body ?ts
              :where [?e :notes/body ?body]
                     [?e :notes/created-at ?ts]]
            (db/db))
       (sort-by second >)
       (take 100)
       (mapv (fn [[e body ts]]
               {:id         e
                :body       body
                :created-at (str ts)}))))

(defn add-note! [body]
  (d/transact (db/conn)
              {:tx-data [{:notes/body       body
                          :notes/created-at (java.util.Date.)}]}))
