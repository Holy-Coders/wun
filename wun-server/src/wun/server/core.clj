(ns wun.server.core
  "Wun phase 0 server entry point. The HTTP transport lives in
   wun.server.http (JDK HttpServer). The brief calls for Pedestal; this
   sandbox can't reach Clojars, so phase 0 ships with a portable transport
   and phase 1 substitutes Pedestal once Clojars is reachable. The wire
   format and intent semantics are unchanged."
  (:require [wun.server.http    :as http]
            [wun.server.intents :as intents])
  (:gen-class))

(defonce ^:private server (atom nil))

(defn start!
  ([] (start! {}))
  ([opts]
   (intents/register-defaults!)
   (when @server
     (http/stop! @server))
   (reset! server (http/start! opts))
   @server))

(defn stop! []
  (when-let [s @server]
    (http/stop! s)
    (reset! server nil)))

(defn -main [& _]
  (start!)
  (println "Wun server listening on http://localhost:8080")
  (println "  GET  /wun     SSE patch stream")
  (println "  POST /intent  intent endpoint (transit-json)")
  @(promise))
