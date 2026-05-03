(ns wun.server.core
  "Wun server entry point. HTTP transport lives in wun.server.http
   (Pedestal on Jetty). All component / screen / intent registries
   live in wun-shared and are populated by namespace load --
   requiring `wun.foundation.components` registers the `:wun/*`
   vocabulary; requiring `wun.app.*` registers the built-in demo
   screens (counter, about, showcase) the framework's own dev loop
   serves at `/`, `/about`, `/showcase`.

   `start!` also auto-mounts the live dashboard at `/_wun/dashboard`
   and wires the showcase live demo's server-side glue, both gated
   on `WUN_PROFILE != prod`. Production deployments run their own
   `start!` from a consumer-app entry point and opt in explicitly."
  (:require [wun.server.http         :as http]
            [wun.server.dashboard    :as dashboard]
            [wun.server.app.showcase :as app-showcase]
            ;; Side-effecting requires populate the open registries.
            wun.foundation.components
            wun.foundation.theme
            wun.forms.intents
            wun.app.counter
            wun.app.about
            wun.app.showcase
            myapp.components)
  (:gen-class))

(defonce ^:private server (atom nil))

(defn- dev-profile? []
  (not= "prod" (System/getenv "WUN_PROFILE")))

(defn start!
  ([] (start! {}))
  ([opts]
   (when @server
     (http/stop! @server))
   (reset! server (http/start! opts))
   (when (dev-profile?)
     ;; Both are idempotent on repeat calls.
     (dashboard/install!)
     (app-showcase/init!))
   @server))

(defn stop! []
  (when-let [s @server]
    (http/stop! s)
    (reset! server nil)))

(defn -main [& _]
  (start!)
  (println "Wun server listening on http://localhost:8080")
  (println "  GET  /            built-in counter demo")
  (println "  GET  /showcase    framework feature showcase (forms · live · fallback · theme)")
  (println "  GET  /_wun/dashboard   live dev dashboard")
  (println "  GET  /wun         SSE patch stream")
  (println "  POST /intent      intent endpoint (transit-json)")
  @(promise))
