(ns myapp.server.migrate
  "Tiny forward-only SQL migration runner for Postgres. Same shape as
   the SQLite variant -- swap to Migratus when you need `down`
   migrations or out-of-order detection."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [next.jdbc       :as jdbc]
            [myapp.log       :as log]))

(def ^:private ensure-table-sql
  "CREATE TABLE IF NOT EXISTS schema_migrations (
     id text PRIMARY KEY,
     applied_at timestamptz NOT NULL DEFAULT now()
   )")

(defn- list-migration-files []
  (when-let [url (io/resource "migrations")]
    (case (.getProtocol url)
      "file"
      (->> (.listFiles (io/file url))
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".sql"))
           (sort-by #(.getName %)))
      "jar"
      (let [conn  (.openConnection url)
            jar   (.getJarFile conn)
            names (->> (enumeration-seq (.entries jar))
                       (map #(.getName %))
                       (filter #(re-matches #"migrations/[^/]+\.sql" %))
                       sort)]
        (for [n names]
          {:name (subs n (count "migrations/"))
           :sql  (slurp (io/resource n))})))))

(defn- already-applied [ds]
  (->> (jdbc/execute! ds ["SELECT id FROM schema_migrations"])
       (map (some-fn :schema_migrations/id :id))
       set))

(defn- file->entry [f]
  (cond
    (instance? java.io.File f) {:name (.getName f) :sql (slurp f)}
    (map? f)                   f
    :else                      (throw (ex-info "unknown migration entry" {:f f}))))

(defn run! [ds]
  (jdbc/execute! ds [ensure-table-sql])
  (let [applied (already-applied ds)
        files   (->> (list-migration-files)
                     (map file->entry)
                     (remove #(applied (str/replace (:name %) #"\.sql$" ""))))]
    (if (empty? files)
      (log/info :migrate.up-to-date)
      (do
        (log/info :migrate.applying :count (count files))
        (jdbc/with-transaction [tx ds]
          (doseq [{:keys [name sql]} files]
            (let [id (str/replace name #"\.sql$" "")]
              (log/info :migrate.apply :id id)
              (jdbc/execute! tx [sql])
              (jdbc/execute! tx ["INSERT INTO schema_migrations (id) VALUES (?)" id]))))))))
