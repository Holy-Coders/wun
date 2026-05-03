(ns myapp.server.auth
  "Server-side auth: bcrypt-hashed users, opaque session tokens.
   Backed by next.jdbc against Postgres."
  (:require [buddy.hashers   :as hashers]
            [next.jdbc       :as jdbc]
            [next.jdbc.sql   :as sql]
            [myapp.server.db :as db])
  (:import  [java.security SecureRandom]))

(def ^:private rng (SecureRandom.))

(defn- random-token []
  (let [bs (byte-array 32)]
    (.nextBytes rng bs)
    (.toString (BigInteger. 1 bs) 16)))

(defn init! [] :ok)

(defn- find-user [email]
  (first (jdbc/execute! (db/ds)
                        ["SELECT id, email, password_hash FROM users WHERE email = ?" email])))

(defn sign-up! [email password]
  (when (find-user email)
    (throw (ex-info "email already registered" {:email email})))
  (let [hash (hashers/derive password)
        row  (sql/insert! (db/ds) :users
                          {:email email :password_hash hash}
                          {:return-keys [:id]})
        id    (or (:users/id row) (:id row))
        token (random-token)]
    (sql/insert! (db/ds) :sessions {:token token :user_id id})
    {:user-id id :token token}))

(defn log-in! [email password]
  (when-let [{:users/keys [id password_hash]} (find-user email)]
    (when (hashers/check password password_hash)
      (let [token (random-token)]
        (sql/insert! (db/ds) :sessions {:token token :user_id id})
        {:user-id id :token token}))))

(defn log-out! [token]
  (jdbc/execute! (db/ds) ["DELETE FROM sessions WHERE token = ?" token]))

(defn current-user [token]
  (first
   (jdbc/execute!
    (db/ds)
    ["SELECT u.id, u.email
        FROM users u JOIN sessions s ON s.user_id = u.id
       WHERE s.token = ?"
     token])))
