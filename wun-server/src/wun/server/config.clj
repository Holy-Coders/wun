(ns wun.server.config
  "Twelve-factor config: read from env vars at startup, validate
   required keys exist, and crash the server early with a useful
   message rather than silently falling back to defaults that
   diverge from production.

   Wun's framework code reads its own env vars directly (e.g.
   `WUN_CSRF_SECRET` in `wun.server.csrf`). This namespace is for
   *app* config -- DB URLs, S3 buckets, third-party API keys.
   The shape:

       (defconfig
         {:db-url            {:required? true}
          :s3-bucket         {:required? true}
          :sentry-dsn        {:required? false :default nil}
          :feature-flag-foo  {:default false :parse parse-bool}})

   ...resolves once at startup, returns a map. Required keys missing
   from the env raise an `ex-info` with `:type :wun/config-error` so
   the entry point can fail fast (and apps can pretty-print the
   missing keys in their `-main`).

   Security posture: this namespace never reads `.env` files or
   anything off disk. Secrets reach the process through environment
   variables only, set by the deployment platform (fly.io secrets,
   Kubernetes Secret, systemd EnvironmentFile, ...). Never
   `println` the resolved map -- it likely contains secrets."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.edn    :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Parsers

(defn parse-bool
  "Lenient bool parser. \"true\", \"1\", \"yes\", \"on\" -> true;
   anything else (including nil, \"false\", \"\") -> false."
  [v]
  (case (some-> v str str/trim str/lower-case)
    ("true" "1" "yes" "on") true
    false))

(defn parse-int [v]
  (when v (try (Long/parseLong (str v)) (catch NumberFormatException _ nil))))

(defn parse-edn [v]
  (when v (try (edn/read-string (str v))
               (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Resolution

(defn- env-key
  "Convert a config key to its env-var name. `:db-url` -> `DB_URL`."
  [k]
  (-> (name k)
      str/upper-case
      (str/replace "-" "_")))

(defn- read-env [k]
  (System/getenv (env-key k)))

(defn- resolve-key [k {:keys [required? default parse env]}]
  (let [raw (or (when env (System/getenv env)) (read-env k))
        val (cond
              (some? raw) (if parse (parse raw) raw)
              :else       default)]
    (if (and required? (nil? val))
      (throw (ex-info (str "missing required config key " k
                           " (env " (or env (env-key k)) ")")
                      {:type :wun/config-error :key k}))
      val)))

(defn resolve
  "Resolve `spec` (a map of key -> {:required? :default :parse :env})
   against the current environment. Throws on missing required keys.
   Returns a frozen map."
  [spec]
  (reduce-kv (fn [m k cfg] (assoc m k (resolve-key k cfg)))
             {} spec))

(defn resolve-many
  "Resolve a sequence of `[spec ...]` maps and merge them in order.
   Lets apps split config across feature-area files."
  [& specs]
  (reduce merge {} (map resolve specs)))

;; ---------------------------------------------------------------------------
;; Reporting

(defn report-missing
  "Pretty-print which required keys are missing, without revealing
   what's set. Useful at the top of `-main` so failures land in the
   user's terminal:

       (try (config/resolve my-spec)
            (catch Exception e
              (config/report-missing my-spec e)
              (System/exit 1)))"
  [spec ex]
  (let [missing (->> spec
                     (filter (fn [[_ {:keys [required?]}]] required?))
                     (filter (fn [[k {:keys [env]}]]
                               (nil? (or (when env (System/getenv env))
                                         (read-env k)))))
                     (mapv first))]
    (binding [*out* *err*]
      (println "wun.config: cannot start, missing required env vars:")
      (doseq [k missing]
        (println (str "  - " (env-key k) "  (config key " k ")")))
      (when ex (println (str "  details: " (.getMessage ex)))))))
