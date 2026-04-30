(ns wun.server.core
  "Wun server entry point. HTTP transport lives in wun.server.http
   (JDK HttpServer; Pedestal lands at phase 1.D once Clojars is
   reachable). All component / screen / intent registries live in
   wun-shared and are populated by namespace load -- requiring
   `wun.foundation.components` registers the `:wun/*` vocabulary;
   requiring `wun.app.counter` registers the demo screen + intents."
  (:require [wun.server.http :as http]
            ;; Side-effecting requires populate the open registries.
            wun.foundation.components
            wun.app.counter
            myapp.components)
  (:gen-class))

(defonce ^:private server (atom nil))

(defn start!
  ([] (start! {}))
  ([opts]
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
