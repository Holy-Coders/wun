(ns myapp.server.db
  "Postgres connection lifecycle. HikariCP-pooled (default 10 conns).
   Migrations run on every boot; the runner is a forward-only,
   directory-scan style under `resources/migrations/*.sql`."
  (:require [clojure.string       :as str]
            [next.jdbc            :as jdbc]
            [next.jdbc.connection :as connection]
            [myapp.config         :as config]
            [myapp.log            :as log]
            [myapp.server.migrate :as migrate])
  (:import  [com.zaxxer.hikari HikariDataSource]))

(defonce ^:private datasource (atom nil))

(defn ds []
  (or @datasource
      (throw (ex-info "myapp.server.db not initialized; call (init!)" {}))))

(defn init! []
  (let [url (or (:database-url config/config)
                "jdbc:postgresql://localhost:5432/myapp?user=myapp&password=myapp")
        ds  (connection/->pool HikariDataSource
                               {:jdbcUrl  url
                                :poolName "myapp-postgres"})]
    (reset! datasource ds)
    (log/info :db.connected :url (str/replace-first
                                  (str url)
                                  #"password=[^&]+"
                                  "password=***"))
    (migrate/run! ds)
    ds))

(defn stop! []
  (when-let [^HikariDataSource ds @datasource]
    (.close ds)
    (reset! datasource nil)))

(defn query [sql-and-params]
  (jdbc/execute! (ds) sql-and-params))

(defn execute! [sql-and-params]
  (jdbc/execute! (ds) sql-and-params))
