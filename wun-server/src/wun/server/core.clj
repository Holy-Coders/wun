(ns wun.server.core
  "Wun phase 0 server: one screen, one SSE channel, one intent endpoint."
  (:require [clojure.core.async :as a]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sse :as sse]
            [wun.server.intents :as intents]
            [wun.server.render :as render]
            [wun.server.state :as state]
            [wun.server.wire :as wire])
  (:gen-class))

(defn- emit-tree!
  "Put a patch envelope onto a single SSE event channel."
  [event-ch envelope]
  (a/put! event-ch
          {:name "patch"
           :data (wire/write-transit-json envelope)}))

(defn- broadcast-current-tree!
  "Re-render the tree from current app state and push :replace-at-root to
   every connected client. Optionally tagged with the resolving intent id."
  ([] (broadcast-current-tree! nil))
  ([resolves-intent]
   (let [tree     (render/counter-screen @state/app-state)
         envelope (wire/replace-root-envelope tree resolves-intent)]
     (doseq [ch @state/connections]
       (emit-tree! ch envelope)))))

(defn- on-stream-ready
  "Called by Pedestal when an SSE connection opens. Registers the channel
   and emits the initial full-tree replacement.

   Note: we do not read from event-ch -- Pedestal reads from it to flush
   SSE events to the response. Disconnected channels stay in the set;
   put! on a closed channel is a silent no-op. Reaping is phase-1 work."
  [event-ch ctx]
  (state/add-connection! event-ch)
  (let [tree     (render/counter-screen @state/app-state)
        envelope (wire/replace-root-envelope tree)]
    (emit-tree! event-ch envelope))
  ctx)

(defn- intent-handler
  "POST /intent: apply the morph, broadcast the new tree to all clients."
  [request]
  (let [raw      (slurp (:body request))
        envelope (wire/read-transit-json raw)
        {:keys [intent params id]} envelope
        before   @state/app-state
        after    (intents/apply-intent before intent params)]
    (when (not= before after)
      (reset! state/app-state after))
    (broadcast-current-tree! id)
    {:status  200
     :headers {"Content-Type" "application/transit+json"}
     :body    (wire/write-transit-json
               {:status :ok :resolves-intent id})}))

(def routes
  (route/expand-routes
   #{["/wun"    :get  (sse/start-event-stream on-stream-ready)
      :route-name :wun-stream]
     ["/intent" :post intent-handler
      :route-name :wun-intent]}))

(defn service-map
  ([] (service-map {}))
  ([{:keys [port host] :or {port 8080 host "0.0.0.0"}}]
   {::http/routes            routes
    ::http/type              :jetty
    ::http/port              port
    ::http/host              host
    ::http/join?             false
    ::http/secure-headers    nil
    ::http/allowed-origins   {:creds           true
                              :allowed-origins (constantly true)}}))

(defonce ^:private server (atom nil))

(defn start!
  ([] (start! {}))
  ([opts]
   (intents/register-defaults!)
   (let [s (-> (service-map opts) http/create-server http/start)]
     (reset! server s)
     s)))

(defn stop! []
  (when-let [s @server]
    (http/stop s)
    (reset! server nil)))

(defn -main [& _]
  (start!)
  (println "Wun server listening on http://localhost:8080")
  (println "  GET  /wun     SSE patch stream")
  (println "  POST /intent  intent endpoint (transit-json)")
  @(promise))
