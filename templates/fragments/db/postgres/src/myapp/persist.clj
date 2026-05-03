(ns myapp.persist
  "Durability for per-connection state slices (Postgres backend).
   Watches `wun.server.state/conn-states` and writes any slice with
   a `:session/user-id` to the `wun_conn_state` table. Cross-instance
   scaling is handled by the sibling `myapp.server.bus` namespace,
   which uses LISTEN / NOTIFY to fan out writes."
  (:require [clojure.edn       :as edn]
            [next.jdbc         :as jdbc]
            [myapp.server.db   :as db]
            [myapp.log         :as log]
            [wun.server.state  :as wun-state]))

(def ^:private excluded-keys #{:session :notes})

(defn- to-persist [state]
  (apply dissoc state excluded-keys))

;; The bus optionally hooks in here -- when present, we NOTIFY on
;; every successful write. Resolved lazily so this ns doesn't pull
;; in `myapp.server.bus` for non-postgres scaffolds (it doesn't ship
;; for sqlite / datomic). Set by `myapp.server.bus/start!`.
(defonce ^:private notify-fn (atom nil))

(defn register-notify-fn! [f]
  (reset! notify-fn f))

(defn write-state! [user-id state]
  (try
    (jdbc/execute!
     (db/ds)
     ["INSERT INTO wun_conn_state (user_id, state_edn, updated_at)
       VALUES (?, ?::jsonb, now())
       ON CONFLICT (user_id) DO UPDATE
         SET state_edn = EXCLUDED.state_edn,
             updated_at = EXCLUDED.updated_at"
      user-id (pr-str (to-persist state))])
    (when-let [f @notify-fn] (f user-id))
    (log/debug :persist.wrote :user-id user-id)
    (catch Throwable e
      (log/warn :persist.write-failed :user-id user-id
                :err (.getMessage e)))))

(defn load-state-by-user [user-id]
  (when user-id
    (try
      (when-let [row (first (jdbc/execute!
                             (db/ds)
                             ["SELECT state_edn FROM wun_conn_state WHERE user_id = ?"
                              user-id]))]
        (edn/read-string (str (:wun_conn_state/state_edn row))))
      (catch Throwable e
        (log/debug :persist.load-failed :user-id user-id
                   :err (.getMessage e))
        nil))))

(defn load-state [_conn-id] nil)

(defn- on-state-change [_conn-id new-state old-state]
  (when-let [user-id (get-in new-state [:session :user-id])]
    (when (not= (to-persist new-state) (to-persist old-state))
      (write-state! user-id new-state))))

(defn init! []
  (wun-state/register-state-watch! on-state-change)
  (log/info :persist.installed))
