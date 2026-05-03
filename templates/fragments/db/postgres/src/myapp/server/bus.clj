(ns myapp.server.bus
  "Cross-instance broadcast via Postgres LISTEN / NOTIFY.

   The story: when instance A processes an intent for user 42, the
   morph mutates A's in-memory slice for that user. The persist
   layer writes the slice to `wun_conn_state`. We then `pg_notify`
   on channel `wun_state_change` with the user-id as payload.

   Every instance (including A) runs this listener thread; each
   receives the notification, looks up its own SSE connections whose
   slice has `:session/user-id` matching the payload, reloads from
   the DB, and re-broadcasts. Instance A sees its own NOTIFY too,
   but the reload is idempotent and the broadcast is a no-op when
   the prior tree already matches.

   Caveats:
   - Sticky sessions are NOT required: any instance can serve any
     user. The same user open in two browsers across two instances
     stays in sync within ~one round-trip of latency.
   - LISTEN holds a dedicated Postgres connection -- this thread
     owns one outside of HikariCP's pool to keep the LISTEN state.
     Reconnects on errors with backoff.
   - The notification payload is just the user-id (cheap). The
     receiver does the reload SELECT itself, so race-conditions
     between two near-simultaneous writes resolve to whichever
     committed last in the DB.
   - SQLite / Datomic backends can't subscribe -- they're
     single-instance by construction. Run the app with one replica."
  (:require [clojure.string   :as str]
            [next.jdbc        :as jdbc]
            [myapp.config     :as config]
            [myapp.log        :as log]
            [myapp.persist    :as persist]
            [myapp.server.db  :as db]
            [wun.server.state :as wun-state])
  (:import [java.sql Connection]
           [org.postgresql PGConnection PGNotification]))

(def ^:private notify-channel "wun_state_change")
(def ^:private listen-poll-ms 1000)
(def ^:private reconnect-backoff-ms 5000)

(defonce ^:private listener-state (atom {:running? false :thread nil}))

;; ---------------------------------------------------------------------------
;; Notify (called from persist/write-state! after a successful write)

(defn notify! [user-id]
  (try
    (jdbc/execute! (db/ds)
                   ["SELECT pg_notify(?, ?)"
                    notify-channel (str user-id)])
    (catch Throwable e
      (log/warn :bus.notify-failed :user-id user-id
                :err (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; Listen loop

(defn- broadcast-fn-resolve
  "Look up `wun.server.http/broadcast-to-conn!` lazily so this ns
   doesn't import http.clj at compile-time (avoids a cycle with the
   server-main require ordering). Returns nil if the var isn't bound
   yet -- the next NOTIFY tick will retry."
  []
  (when-let [v (resolve 'wun.server.http/broadcast-to-conn!)]
    @v))

(defn- ssc-channels-for-user [user-id]
  ;; Find every conn-id whose slice currently has this user-id.
  ;; Multiple conns can map to the same user (multi-tab, multi-device).
  (->> @wun-state/conn-states
       (keep (fn [[cid s]]
               (when (= user-id (get-in s [:session :user-id])) cid)))
       set))

(defn- handle-notification! [^PGNotification n]
  (let [payload (.getParameter n)
        user-id (try (Long/parseLong payload) (catch Throwable _ nil))]
    (when user-id
      (when-let [saved (persist/load-state-by-user user-id)]
        (let [conn-ids (ssc-channels-for-user user-id)]
          (doseq [cid conn-ids]
            (wun-state/swap-state-for!
             cid
             (fn [s]
               ;; Merge saved state into the live slice, preserving
               ;; whatever's session-only (token, email).
               (merge s saved))))
          (when-let [broadcast! (broadcast-fn-resolve)]
            (doseq [cid conn-ids]
              (broadcast! cid))))))))

(defn- pg-conn ^Connection []
  ;; Open a dedicated raw JDBC connection -- the Hikari pool would
  ;; multiplex connections across requests and lose LISTEN state.
  (jdbc/get-connection {:jdbcUrl (:database-url config/config)}))

(defn- listen-once! []
  (with-open [^Connection raw (pg-conn)]
    (with-open [stmt (.createStatement raw)]
      (.execute stmt (str "LISTEN " notify-channel))
      (log/info :bus.listening :channel notify-channel)
      (let [pg ^PGConnection (.unwrap raw PGConnection)]
        (loop []
          (when (:running? @listener-state)
            ;; PG drives notification delivery on the next round-trip,
            ;; so a cheap SELECT keeps the link warm and forces
            ;; getNotifications to surface anything queued.
            (try
              (with-open [s (.createStatement raw)]
                (.execute s "SELECT 1"))
              (catch Throwable _ nil))
            (when-let [^"[Lorg.postgresql.PGNotification;" notifs (.getNotifications pg)]
              (doseq [n notifs] (handle-notification! n)))
            (Thread/sleep listen-poll-ms)
            (recur)))))))

(defn- listen-loop! []
  (loop []
    (when (:running? @listener-state)
      (try
        (listen-once!)
        (catch Throwable e
          (log/warn :bus.listen-failed :err (.getMessage e))
          (Thread/sleep reconnect-backoff-ms)))
      (recur))))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defn start! []
  (when (compare-and-set! listener-state
                          (assoc @listener-state :running? false)
                          (assoc @listener-state :running? true))
    (let [t (doto (Thread. ^Runnable listen-loop! "wun-bus-listener")
              (.setDaemon true))]
      (swap! listener-state assoc :thread t)
      (.start t)
      (persist/register-notify-fn! notify!)
      (log/info :bus.started))))

(defn stop! []
  (swap! listener-state assoc :running? false)
  (when-let [^Thread t (:thread @listener-state)]
    (.interrupt t))
  (persist/register-notify-fn! nil)
  (log/info :bus.stopped))
