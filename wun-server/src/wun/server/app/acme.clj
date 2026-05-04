(ns wun.server.app.acme
  "Server-only side of the framework's built-in 'fake SaaS' demo.

   Mirrors `templates/fragments/showcase/src/myapp/server/acme.clj`
   verbatim except for `:wun.app.acme/*` keys instead of
   `:myapp.acme/*` and a `wun.app.acme.*` ns prefix instead of
   `myapp.acme.*`, so the framework's own `wun dev` loop can serve
   the same demo with no consumer-app present.

   Owns four single-source-of-truth atoms (kept here, not in any
   per-conn slice):

     users-table  -- username -> {:role :avatar :credential ...}
                     credential is PBKDF2-SHA256 + per-user salt;
                     real auth, not a comparison-by-equality demo.
     sessions     -- token -> username, mutated on log-in / log-out.
     metrics      -- username -> {:mrr :signups-today :conversion-pct
                                  :sparkline}, jittered every tick.
     activity     -- vec of {:ts :who :event :tone}, capped at 20.

   Per-conn state slice mirrors a subset of these (`:wun.app.acme/me`,
   `:session`, `:wun.app.acme/dashboard`, `:wun.app.acme/activity`,
   `:wun.app.acme/online`) so the existing per-conn render path
   doesn't need a second source. The mirror is rebuilt on each tick
   and on relevant connect / intent events; it is never the source.

   `init!` is idempotent: seed-users + seed-metrics, install the
   init-state-fn for session-token resume, register the telemetry
   sink that handles login / logout navigation + presence, and start
   the realtime tick. Safe to call repeatedly."
  (:require [clojure.tools.logging :as log]
            [wun.server.state      :as state]
            [wun.server.session    :as session]
            [wun.server.telemetry  :as telemetry])
  (:import  [javax.crypto SecretKeyFactory]
            [javax.crypto.spec PBEKeySpec]
            [java.security MessageDigest SecureRandom]
            [java.util.concurrent Executors TimeUnit
                                  ScheduledExecutorService
                                  ScheduledFuture]))

;; ---------------------------------------------------------------------------
;; Crypto

(def ^:private pbkdf2-iters     120000)
(def ^:private pbkdf2-key-bits  256)
(def ^:private salt-bytes       16)

(defn- random-salt ^bytes []
  (let [b (byte-array salt-bytes)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn- pbkdf2 ^bytes [^chars password ^bytes salt]
  (let [spec (PBEKeySpec. password salt pbkdf2-iters pbkdf2-key-bits)
        skf  (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")]
    (.getEncoded (.generateSecret skf spec))))

(defn- hash-password [^String password]
  (let [salt (random-salt)
        h    (pbkdf2 (.toCharArray password) salt)]
    {:salt salt :hash h}))

(defn- verify-password? [^String password {:keys [salt hash]}]
  (and password salt hash
       (MessageDigest/isEqual ^bytes hash
                              (pbkdf2 (.toCharArray password) salt))))

;; ---------------------------------------------------------------------------
;; Seed data

(def seed-users
  "Demo accounts. Password matches username on purpose — the login
   screen displays them. Edit this vec (and call `init!` again from
   the REPL) to rotate."
  [{:username "alice" :password "alice" :role "admin"   :avatar "AL"
    :seed-mrr 14523   :seed-signups 47  :seed-conv 3.2}
   {:username "bob"   :password "bob"   :role "manager" :avatar "BB"
    :seed-mrr  8321   :seed-signups 18  :seed-conv 2.4}
   {:username "carol" :password "carol" :role "viewer"  :avatar "CR"
    :seed-mrr 22918   :seed-signups 91  :seed-conv 4.1}])

(defonce ^:private users-table (atom {}))   ;; username -> user record (with credential)
(defonce ^:private sessions    (atom {}))   ;; token -> username
(defonce ^:private metrics     (atom {}))   ;; username -> metrics map
(defonce ^:private activity    (atom []))   ;; vec of events, newest first

(def ^:private dashboard-screen-key :wun.app.acme/dashboard)
(def ^:private login-screen-key     :wun.app.acme/login)

(defn- seed-metrics-table []
  (into {}
        (for [{:keys [username seed-mrr seed-signups seed-conv]} seed-users]
          [username {:mrr            seed-mrr
                     :signups-today  seed-signups
                     :conversion-pct seed-conv
                     :sparkline      (vec (repeatedly 24 #(+ 40 (rand-int 60))))}])))

(defn- seed-users-table []
  (into {}
        (for [{:keys [username password] :as u} seed-users]
          [username (-> u
                        (dissoc :password :seed-mrr :seed-signups :seed-conv)
                        (assoc  :credential (hash-password password)))])))

;; ---------------------------------------------------------------------------
;; Sessions

(defn- new-session-for! [username]
  (let [token (session/issue-token)]
    (swap! sessions assoc token username)
    token))

(defn- end-session! [token]
  (when token
    (swap! sessions dissoc token)
    (session/revoke! token)))

(defn- user-for-token [token]
  (some->> token (get @sessions) (get @users-table)))

(defn- public-user [u]
  (when u (select-keys u [:username :role :avatar])))

;; ---------------------------------------------------------------------------
;; Live state mirroring
;;
;; The dashboard is the only screen that needs cross-conn fan-out; on
;; every tick (and on every connect / disconnect / login / logout) we
;; rebuild the per-conn slice from the source atoms and broadcast.
;;
;; `live-conn-ids` filters out channels that Pedestal has already
;; closed but the periodic gc-tick hasn't reaped yet, so refreshes
;; don't briefly show zombies in the presence list.

(defn- on-dashboard? [conn-meta]
  (= dashboard-screen-key (peek (:screen-stack conn-meta))))

(defn- live-conn-ids []
  (->> @state/connections
       (remove (fn [[ch _]] (state/closed? ch)))
       (map second)
       (filter on-dashboard?)
       (keep :conn-id)
       distinct))

(defn- conn-username [conn-id]
  (some-> (state/state-for conn-id) :wun.app.acme/me :username))

(defn- online-list []
  (->> (live-conn-ids)
       (map (fn [cid]
              (when-let [u (some-> (state/state-for cid) :wun.app.acme/me)]
                (select-keys u [:username :avatar :role]))))
       (remove nil?)
       distinct
       vec))

(defn- inject-online! []
  (let [present (online-list)]
    (doseq [cid (live-conn-ids)]
      (state/swap-state-for! cid assoc :wun.app.acme/online present))))

(defn- inject-activity! []
  (let [feed (vec (take 20 @activity))]
    (doseq [cid (live-conn-ids)]
      (state/swap-state-for! cid assoc :wun.app.acme/activity feed))))

(defn- inject-metrics-for! [conn-id username]
  (when-let [m (get @metrics username)]
    (state/swap-state-for! conn-id assoc :wun.app.acme/dashboard m)))

(defn- broadcast-dashboard! []
  (when-let [bcast (try (requiring-resolve 'wun.server.http/broadcast-to-conn!)
                        (catch Throwable _ nil))]
    (doseq [cid (live-conn-ids)]
      (bcast cid))))

(defn- replace-screen-on! [conn-id target]
  (when-let [ch (state/channel-by-conn-id conn-id)]
    (state/replace-screen! ch target)
    (when-let [bcast (try (requiring-resolve 'wun.server.http/broadcast-to-conn!)
                          (catch Throwable _ nil))]
      (bcast conn-id))))

;; ---------------------------------------------------------------------------
;; init-state-fn — re-hydrate :wun.app.acme/me + :session from the
;; resumed session token so refresh keeps you logged in.

(defn- init-slice [_conn-id ctx]
  (when-let [u (user-for-token (:session-token ctx))]
    {:wun.app.acme/me (public-user u)
     :session         {:token (:session-token ctx)}}))

;; ---------------------------------------------------------------------------
;; Auth (called from the cljc morph via requiring-resolve)

(defn log-in!
  "Validate credentials and (on success) mint a fresh session token.
   Returns `{:ok user-public-record :token \"...\"}` on success or
   `{:error \"...\"}` on failure. Pure return-value side from the cljc
   morph's perspective — the morph then folds the result into state."
  [_state {:keys [username password]}]
  (let [u (get @users-table username)]
    (cond
      (or (nil? username) (= "" username))     {:error "Enter a username."}
      (or (nil? password) (= "" password))     {:error "Enter a password."}
      (nil? u)                                 {:error "Unknown user."}
      (not (verify-password? password (:credential u)))
                                               {:error "Wrong password."}
      :else
      {:ok    (public-user u)
       :token (new-session-for! username)})))

(defn log-out!
  "Revoke the session token and clear it from the in-memory table.
   Idempotent. Always returns nil."
  [state _params]
  (end-session! (get-in state [:session :token]))
  nil)

;; ---------------------------------------------------------------------------
;; Telemetry sink

(defn- on-connect [{:keys [conn-id screen-key]}]
  (when (= dashboard-screen-key screen-key)
    (let [me (some-> (state/state-for conn-id) :wun.app.acme/me)]
      (if me
        (do (inject-metrics-for! conn-id (:username me))
            (inject-online!)
            (inject-activity!)
            (broadcast-dashboard!))
        ;; Cold visit / expired session — bounce to login.
        (replace-screen-on! conn-id login-screen-key)))))

(defn- on-disconnect [_]
  ;; Eagerly refresh presence + broadcast so the remaining viewers see
  ;; the count drop without waiting for the next tick.
  (inject-online!)
  (broadcast-dashboard!))

(defn- on-intent-applied [{:keys [conn-id intent]}]
  (case intent
    :wun.app.acme/log-in
    (when-let [me (some-> (state/state-for conn-id) :wun.app.acme/me)]
      (replace-screen-on! conn-id dashboard-screen-key)
      (inject-metrics-for! conn-id (:username me))
      (inject-online!)
      (inject-activity!)
      (broadcast-dashboard!))

    :wun.app.acme/log-out
    (do (replace-screen-on! conn-id login-screen-key)
        (inject-online!)
        (broadcast-dashboard!))

    nil))

(defn- acme-sink [event-key payload]
  (case event-key
    :wun/connect         (on-connect payload)
    :wun/disconnect      (on-disconnect payload)
    :wun/intent.applied  (on-intent-applied payload)
    nil))

;; ---------------------------------------------------------------------------
;; Realtime tick — drives the activity feed + metric drift.

(def ^:private fake-actors
  ["@avery" "@jordan" "@sage" "@maya" "@kai"
   "@river" "@quinn" "@noor" "@finn" "@iris"])

(def ^:private fake-events
  [{:event "upgraded their plan to Pro"     :tone :success}
   {:event "completed onboarding"            :tone :success}
   {:event "added 3 teammates"               :tone :info}
   {:event "exported a quarterly report"     :tone :info}
   {:event "connected the Stripe integration":tone :info}
   {:event "left a 5-star review"            :tone :success}
   {:event "renewed for another year"        :tone :success}
   {:event "hit their usage limit"           :tone :warning}
   {:event "invited a new admin"             :tone :info}
   {:event "viewed the pricing page"         :tone :info}])

(defn- random-event []
  (merge {:ts  (System/currentTimeMillis)
          :who (rand-nth fake-actors)}
         (rand-nth fake-events)))

(defn- jitter [n delta]
  (max 0 (+ n (- (rand-int (* 2 delta)) delta))))

(defn- jitter-pct [pct delta]
  (-> (+ pct (- (* 2.0 delta (rand)) delta))
      (max 0.0)
      (* 100) (Math/round) (/ 100.0)))

(defn- tick-metrics! []
  (swap! metrics
         (fn [m]
           (into {}
                 (for [[u {:keys [mrr signups-today conversion-pct sparkline]}] m]
                   [u {:mrr            (jitter mrr 25)
                       :signups-today  (max 0 (+ signups-today (rand-int 2)))
                       :conversion-pct (jitter-pct conversion-pct 0.08)
                       :sparkline      (-> (vec (rest sparkline))
                                           (conj (+ 30 (rand-int 70))))}])))))

(defn- tick-activity! []
  (swap! activity (fn [v] (vec (take 20 (cons (random-event) v))))))

(defn- inject-and-broadcast-all! []
  (doseq [cid (live-conn-ids)]
    (when-let [u (conn-username cid)]
      (inject-metrics-for! cid u)))
  (inject-activity!)
  (inject-online!)
  (broadcast-dashboard!))

(defn- tick-once! []
  (try
    (tick-metrics!)
    (tick-activity!)
    (inject-and-broadcast-all!)
    (catch Throwable t
      (log/warn t "wun.server.app.acme: tick failed"))))

(defonce ^:private tick-pool   (atom nil))
(defonce ^:private tick-handle (atom nil))

(defn- start-tick! []
  (when-let [^ScheduledFuture h @tick-handle]
    (.cancel h false))
  (when-let [^ScheduledExecutorService p @tick-pool]
    (.shutdownNow p))
  (let [pool (Executors/newSingleThreadScheduledExecutor)]
    (reset! tick-pool   pool)
    (reset! tick-handle
            (.scheduleAtFixedRate pool ^Runnable tick-once!
                                  1 1 TimeUnit/SECONDS))))

;; ---------------------------------------------------------------------------
;; init

(defonce ^:private installed? (atom false))

(defn init!
  "Idempotent: seed the in-memory tables, register the init-state-fn
   so session-token resume rehydrates `:wun.app.acme/me`, register
   the telemetry sink that handles navigation + presence on the
   dashboard, and start the 1Hz realtime tick. Safe to call
   repeatedly — re-runs are no-ops thanks to a `compare-and-set!`
   gate."
  []
  (when (compare-and-set! installed? false true)
    (reset! users-table (seed-users-table))
    (reset! metrics     (seed-metrics-table))
    (state/register-init-state-fn! init-slice)
    (telemetry/register-sink! acme-sink)
    (start-tick!)
    (log/info "wun.server.app.acme: SaaS demo wiring installed"
              {:users (mapv :username seed-users)}))
  :ok)
