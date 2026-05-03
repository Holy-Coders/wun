(ns myapp.log
  "Tiny structured-logging helper. Emits one JSON object per line on
   stdout. No SLF4J / logback dependency -- container log collectors
   pick up stdout and parse JSON natively. Replace with a real logger
   (mulog, clojure.tools.logging) when you outgrow this."
  (:require [clojure.data.json :as json]
            [myapp.config      :as config]))

(def ^:private levels
  {:debug 10 :info 20 :warn 30 :error 40})

(defn- enabled? [level]
  (>= (get levels level 0)
      (get levels (:log-level config/config) 20)))

(defn- now-iso []
  (.toString (java.time.Instant/now)))

(defn log [level event & {:as data}]
  (when (enabled? level)
    (let [line (json/write-str
                (merge {:ts    (now-iso)
                        :level (name level)
                        :event (str event)}
                       data))]
      (locking *out* (println line)))))

(defn debug [event & data] (apply log :debug event data))
(defn info  [event & data] (apply log :info  event data))
(defn warn  [event & data] (apply log :warn  event data))
(defn error [event & data] (apply log :error event data))
