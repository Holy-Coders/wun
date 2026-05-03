(ns myapp.config
  "Environment-driven configuration. Read once at startup; the resulting
   map is the source of truth for the running process. Defaults match
   `wun dev` so a fresh checkout boots without any env vars set."
  (:require [clojure.string :as str]))

(defn- env
  ([k] (env k nil))
  ([k default] (or (System/getenv k) default)))

(defn- parse-int [s default]
  (if (str/blank? s)
    default
    (try (Integer/parseInt s) (catch NumberFormatException _ default))))

(def config
  {:port            (parse-int (env "PORT") 8080)
   :host            (env "HOST" "0.0.0.0")
   :static          (env "WUN_STATIC" "public")
   :log-level       (keyword (env "LOG_LEVEL" "info"))
   :session-secret  (env "SESSION_SECRET" "dev-only-change-me-in-prod")
   :database-url    (env "DATABASE_URL")})
