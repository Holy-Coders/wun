(ns myapp.server.db
  "SQLite connection lifecycle. HikariCP-pooled, but capped at 1 since
   SQLite serializes writers anyway -- a larger pool just queues
   contended writes inside the JVM instead of inside SQLite. The DB
   file is created on first start; migrations run on every boot."
  (:require [next.jdbc            :as jdbc]
            [next.jdbc.connection :as connection]
            [myapp.config         :as config]
            [myapp.log            :as log]
            [myapp.server.migrate :as migrate])
  (:import  [com.zaxxer.hikari HikariDataSource]))

(defonce ^:private datasource (atom nil))

(defn ds
  "Return the live HikariDataSource. Throws if `init!` hasn't run yet."
  []
  (or @datasource
      (throw (ex-info "myapp.server.db not initialized; call (init!)" {}))))

(defn- ensure-data-dir! [^String url]
  ;; jdbc:sqlite:./data/foo.db -> ./data/  (create if missing).
  (when-let [path (second (re-find #"jdbc:sqlite:(.+)$" url))]
    (let [parent (.getParent (java.io.File. path))]
      (when (and parent (not= "" parent))
        (.mkdirs (java.io.File. parent))))))

(defn init! []
  (let [url (or (:database-url config/config)
                "jdbc:sqlite:./data/myapp.db")]
    (ensure-data-dir! url)
    (let [ds (connection/->pool HikariDataSource
                                {:jdbcUrl           url
                                 :maximumPoolSize   1
                                 :poolName          "myapp-sqlite"})]
      (reset! datasource ds)
      (log/info :db.connected :url url)
      (migrate/run! ds)
      ds)))

(defn stop! []
  (when-let [^HikariDataSource ds @datasource]
    (.close ds)
    (reset! datasource nil)))

(defn query
  "Run a SELECT and return rows as maps. Convenience wrapper so
   callers don't have to require next.jdbc directly."
  [sql-and-params]
  (jdbc/execute! (ds) sql-and-params))

(defn execute!
  "Run an INSERT / UPDATE / DELETE. Returns the next.jdbc result."
  [sql-and-params]
  (jdbc/execute! (ds) sql-and-params))
