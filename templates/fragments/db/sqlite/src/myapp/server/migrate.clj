(ns myapp.server.migrate
  "Tiny forward-only SQL migration runner. Scans `resources/migrations/`
   on the classpath for `*.sql` files, applies any whose id (filename
   sans `.sql`) is not yet recorded in `schema_migrations`, in lexical
   order, in a single transaction. No `down` migrations -- write a new
   forward migration to undo. Replace with Migratus when you need
   anything fancier."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [next.jdbc       :as jdbc]
            [myapp.log       :as log]))

(def ^:private ensure-table-sql
  "CREATE TABLE IF NOT EXISTS schema_migrations (
     id text PRIMARY KEY,
     applied_at text NOT NULL DEFAULT (datetime('now'))
   )")

(defn- list-migration-files []
  ;; Walk the classpath resource dir. We use io/resource on the dir
  ;; itself, which works for both filesystem and jar-loaded classpaths.
  (when-let [url (io/resource "migrations")]
    (let [proto (.getProtocol url)]
      (case proto
        "file"
        (->> (.listFiles (io/file url))
             (filter #(.isFile %))
             (filter #(str/ends-with? (.getName %) ".sql"))
             (sort-by #(.getName %)))

        "jar"
        ;; Inside an uberjar: walk the JarFile entries under "migrations/".
        (let [conn  (.openConnection url)
              jar   (.getJarFile conn)
              names (->> (enumeration-seq (.entries jar))
                         (map #(.getName %))
                         (filter #(re-matches #"migrations/[^/]+\.sql" %))
                         sort)]
          (for [n names]
            ;; Stash both name + slurped content; caller doesn't need
            ;; an actual File for jar entries.
            {:name (subs n (count "migrations/"))
             :sql  (slurp (io/resource n))}))))))

(defn- already-applied [ds]
  (->> (jdbc/execute! ds ["SELECT id FROM schema_migrations"])
       (map (some-fn :schema_migrations/id :SCHEMA_MIGRATIONS/ID :id))
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
