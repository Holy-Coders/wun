(ns myapp.persist
  "Durability for per-connection state slices. Watches
   `wun.server.state/conn-states`; whenever a slice has a
   `:session/user-id`, writes the (session-stripped) slice to the
   `wun_conn_state` table.

   What this enables today:
     * `state/conn-states` has a durable mirror -- after a server
       restart you can `(load-state-by-user user-id)` to inspect what
       a user last had.
     * Cross-instance scaling: see `myapp.server.bus`. When the
       Postgres backend is selected, a NOTIFY on every write lets
       sibling instances reload the slice and re-broadcast to their
       attached SSE channels.

   What this does NOT do yet (follow-up):
     * Auto-rehydrate on SSE reconnect. The current SSE handshake
       doesn't carry a session token, so we can't tie a fresh conn-id
       back to a saved user-id at connect time. Once the web client
       sends the session cookie on connect, the init-state-fn in
       server/main.clj will call `(load-state-by-user user-id)` and
       fold the result into the new slice. For now the web client's
       existing localStorage hot-cache handles reload-survival.

   What gets persisted:
     * The whole state slice except `:session` (which is regenerated
       on each login from the DB-backed `sessions` table) and
       `:notes` (which is re-fetched from its own canonical table so
       a stale snapshot doesn't show up after restart). Apps that
       want a different shape pass `:persist/exclude #{...}` in
       their init-state-fn or override `excluded-keys`."
  (:require [clojure.edn       :as edn]
            [next.jdbc         :as jdbc]
            [myapp.server.db   :as db]
            [myapp.log         :as log]
            [wun.server.state  :as wun-state]))

(def ^:private excluded-keys #{:session :notes})

(defn- to-persist [state]
  (apply dissoc state excluded-keys))

(defn write-state!
  "Synchronously upsert the (session-stripped) state slice for
   `user-id`. Idempotent. Errors are logged and swallowed -- a write
   failure should not propagate up into the intent response, since
   the in-memory slice is still authoritative."
  [user-id state]
  (try
    (jdbc/execute!
     (db/ds)
     ["INSERT INTO wun_conn_state (user_id, state_edn, updated_at)
       VALUES (?, ?, datetime('now'))
       ON CONFLICT(user_id) DO UPDATE
         SET state_edn = excluded.state_edn,
             updated_at = excluded.updated_at"
      user-id (pr-str (to-persist state))])
    (log/debug :persist.wrote :user-id user-id)
    (catch Throwable e
      (log/warn :persist.write-failed :user-id user-id
                :err (.getMessage e)))))

(defn load-state-by-user
  "Return the saved state map for `user-id`, or nil if nothing's
   saved (or the table doesn't exist)."
  [user-id]
  (when user-id
    (try
      (when-let [row (first (jdbc/execute!
                             (db/ds)
                             ["SELECT state_edn FROM wun_conn_state WHERE user_id = ?"
                              user-id]))]
        (edn/read-string (:wun_conn_state/state_edn row)))
      (catch Throwable e
        (log/debug :persist.load-failed :user-id user-id
                   :err (.getMessage e))
        nil))))

(defn load-state
  "Hook for the init-state-fn in server/main.clj. Today this is
   conn-id-only and we don't track conn-id <-> user-id, so it's a
   no-op. Once the SSE handshake carries a session token we'll thread
   that user-id through here."
  [_conn-id]
  nil)

(defn- on-state-change [_conn-id new-state old-state]
  (when-let [user-id (get-in new-state [:session :user-id])]
    ;; Skip writes when the persisted shape didn't change (avoids
    ;; thrashing the DB on session-only writes like a re-login).
    (when (not= (to-persist new-state) (to-persist old-state))
      (write-state! user-id new-state))))

(defn init! []
  ;; Idempotent: re-running just replaces the watch fn.
  (wun-state/register-state-watch! on-state-change)
  (log/info :persist.installed))
