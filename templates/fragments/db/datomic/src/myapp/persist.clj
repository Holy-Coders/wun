(ns myapp.persist
  "Durability for per-connection state slices (Datomic backend).
   Watches `wun.server.state/conn-states`; whenever a slice has a
   `:session/user-id`, transacts the (session-stripped) slice as
   EDN under `:wun.conn-state/edn` keyed by `:wun.conn-state/user`."
  (:require [clojure.edn        :as edn]
            [datomic.client.api :as d]
            [myapp.server.db    :as db]
            [myapp.log          :as log]
            [wun.server.state   :as wun-state]))

(def ^:private excluded-keys #{:session :notes})

(defn- to-persist [state]
  (apply dissoc state excluded-keys))

(defn write-state! [user-id state]
  (try
    (d/transact (db/conn)
                {:tx-data
                 [{:wun.conn-state/user        user-id
                   :wun.conn-state/edn         (pr-str (to-persist state))
                   :wun.conn-state/updated-at  (java.util.Date.)}]})
    (log/debug :persist.wrote :user-id user-id)
    (catch Throwable e
      (log/warn :persist.write-failed :user-id user-id
                :err (.getMessage e)))))

(defn load-state-by-user [user-id]
  (when user-id
    (try
      (when-let [edn-str (ffirst
                          (d/q '[:find ?edn
                                 :in $ ?user
                                 :where [?e :wun.conn-state/user ?user]
                                        [?e :wun.conn-state/edn ?edn]]
                               (db/db) user-id))]
        (edn/read-string edn-str))
      (catch Throwable _ nil))))

(defn load-state [_conn-id] nil)

(defn- on-state-change [_conn-id new-state old-state]
  (when-let [user-id (get-in new-state [:session :user-id])]
    (when (not= (to-persist new-state) (to-persist old-state))
      (write-state! user-id new-state))))

(defn init! []
  (wun-state/register-state-watch! on-state-change)
  (log/info :persist.installed))
