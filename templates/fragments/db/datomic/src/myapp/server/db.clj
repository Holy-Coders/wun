(ns myapp.server.db
  "Datomic Local connection lifecycle. In-process, no transactor; data
   persists to `./data/datomic` (or `/app/data/datomic` in Docker).
   Schema is data-driven and idempotent -- re-transacting an existing
   `:db/ident` is a no-op."
  (:require [clojure.edn        :as edn]
            [clojure.java.io    :as io]
            [clojure.string     :as string]
            [datomic.client.api :as d]
            [myapp.config       :as config]
            [myapp.log          :as log]))

(defonce ^:private state (atom nil))

(defn client []
  (or (:client @state)
      (throw (ex-info "myapp.server.db not initialized; call (init!)" {}))))

(defn conn []
  (or (:conn @state)
      (throw (ex-info "myapp.server.db not initialized; call (init!)" {}))))

(defn db [] (d/db (conn)))

(defn- ensure-data-dir! []
  (.mkdirs (io/file "data" "datomic")))

(defn- read-schema
  "Read every `.edn` file under `resources/datomic/`, expecting each to
   be a top-level vector of schema maps, and concat them. This lets
   overlays (notes, auth, ...) ship independent files without sharing
   a single brittle schema.edn."
  []
  (when-let [url (io/resource "datomic")]
    (case (.getProtocol url)
      "file"
      (->> (.listFiles (io/file url))
           (filter #(.isFile %))
           (filter #(string/ends-with? (.getName %) ".edn"))
           (sort-by #(.getName %))
           (mapcat #(try (edn/read-string (slurp %))
                         (catch Throwable _ [])))
           vec)
      "jar"
      (let [conn  (.openConnection url)
            jar   (.getJarFile conn)
            names (->> (enumeration-seq (.entries jar))
                       (map #(.getName %))
                       (filter #(re-matches #"datomic/[^/]+\.edn" %))
                       sort)]
        (->> names
             (mapcat (fn [n] (try (edn/read-string (slurp (io/resource n)))
                                  (catch Throwable _ []))))
             vec)))))

(defn init! []
  (ensure-data-dir!)
  (let [client    (d/client {:server-type :datomic-local
                             :system      "myapp"
                             :storage-dir "data/datomic"})
        db-name   "myapp"
        _         (d/create-database client {:db-name db-name})
        conn      (d/connect client {:db-name db-name})
        schema    (read-schema)]
    (when (seq schema)
      (d/transact conn {:tx-data schema}))
    (reset! state {:client client :conn conn})
    (log/info :db.connected :backend :datomic-local :system "myapp")
    @state))

(defn stop! []
  (reset! state nil))
