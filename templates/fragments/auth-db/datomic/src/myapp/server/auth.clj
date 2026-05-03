(ns myapp.server.auth
  "Server-side auth: bcrypt-hashed users, opaque session tokens.
   Backed by Datomic Local. Schema fragment lives in
   `resources/datomic/schema.edn` (appended by the auth-db/datomic
   overlay)."
  (:require [buddy.hashers      :as hashers]
            [datomic.client.api :as d]
            [myapp.server.db    :as db])
  (:import  [java.security SecureRandom]))

(def ^:private rng (SecureRandom.))

(defn- random-token []
  (let [bs (byte-array 32)]
    (.nextBytes rng bs)
    (.toString (BigInteger. 1 bs) 16)))

(defn init! [] :ok)

(defn- find-user [email]
  (first
   (d/q '[:find  ?e ?email ?hash
          :in    $ ?email
          :where [?e :users/email ?email]
                 [?e :users/password-hash ?hash]]
        (db/db) email)))

(defn sign-up! [email password]
  (when (find-user email)
    (throw (ex-info "email already registered" {:email email})))
  (let [hash      (hashers/derive password)
        token     (random-token)
        tx-result (d/transact (db/conn)
                              {:tx-data [{:db/id              "u"
                                          :users/email         email
                                          :users/password-hash hash
                                          :users/created-at    (java.util.Date.)}
                                         {:sessions/token     token
                                          :sessions/user      "u"
                                          :sessions/created-at (java.util.Date.)}]})
        user-id   (-> tx-result :tempids (get "u"))]
    {:user-id user-id :token token}))

(defn log-in! [email password]
  (when-let [[user-id _ hash] (find-user email)]
    (when (hashers/check password hash)
      (let [token (random-token)]
        (d/transact (db/conn)
                    {:tx-data [{:sessions/token       token
                                :sessions/user        user-id
                                :sessions/created-at  (java.util.Date.)}]})
        {:user-id user-id :token token}))))

(defn- session-eid [token]
  (ffirst (d/q '[:find  ?s
                 :in    $ ?token
                 :where [?s :sessions/token ?token]]
               (db/db) token)))

(defn log-out! [token]
  (when-let [eid (session-eid token)]
    (d/transact (db/conn) {:tx-data [[:db/retractEntity eid]]})))

(defn current-user [token]
  (first
   (d/q '[:find  ?u ?email
          :in    $ ?token
          :where [?s :sessions/token ?token]
                 [?s :sessions/user ?u]
                 [?u :users/email ?email]]
        (db/db) token)))

(defn load-session-by-token
  "Resolve an opaque session token to `{:user-id, :email, :token}`,
   or nil if the token doesn't match a live row. Called by the
   init-state-fn during SSE handshake to rehydrate a slice."
  [token]
  (when token
    (try
      (when-let [[user-id email] (current-user token)]
        {:user-id user-id :email email :token token})
      (catch Throwable _ nil))))
