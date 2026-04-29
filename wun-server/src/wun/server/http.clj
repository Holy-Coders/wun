(ns wun.server.http
  "HTTP transport on top of the Pedestal interceptor chain.

   Routes:
     GET  /wun     SSE patch stream (sse/start-event-stream)
     POST /intent  transit-json intent envelope
     <fallback>    static files served by a tail interceptor

   The wire format, intent semantics, and component vocabulary are
   identical to the JDK-HttpServer transport this replaces -- only the
   transport (and the threading model) differs. Pedestal manages the
   per-connection event channel via NIO, so we no longer block one OS
   thread per SSE connection.

   Threading: Pedestal owns the SSE event channel. We put envelopes on
   it via core.async/offer!; Pedestal reads and flushes to the
   response. Disconnected channels stay in the connection set; offer!
   becomes a silent no-op. Eviction is a later-phase concern.

   Static files use a hand-rolled interceptor rather than
   ::http/file-path because the latter (a) intercepts requests before
   route matching and (b) lets the URI determine MIME type so that
   `/` -> index.html ends up as application/octet-stream. The custom
   interceptor sits after routing so /wun and /intent are not
   pre-empted, resolves directories to index.html, and pins
   Content-Type from the resolved file's extension."
  (:require [clojure.core.async    :as a]
            [io.pedestal.http      :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sse  :as sse]
            [io.pedestal.interceptor :as interceptor]
            [wun.diff              :as diff]
            [wun.intents           :as intents]
            [wun.screens           :as screens]
            [wun.server.state      :as state]
            [wun.server.wire       :as wire])
  (:import  [java.io File]))

;; ---------------------------------------------------------------------------
;; Tree + broadcast

(defn- current-tree []
  ;; TODO(phase 1.D-routing): pick screen by request path.
  (screens/render :counter/main @state/app-state))

(defn- broadcast-to-channel!
  "Diff `ch`'s prior tree against the current tree and enqueue a patch
   envelope iff there are patches or an intent to resolve. Always
   carries the current screen state so the client can mirror it for
   optimistic prediction. Updates the stored prior on successful
   enqueue."
  [ch resolves-intent]
  (let [tree    (current-tree)
        prior   (state/prior-tree ch)
        patches (diff/diff prior tree)]
    (when (or (seq patches) resolves-intent)
      (let [env  (wire/patch-envelope patches
                                      {:resolves-intent resolves-intent
                                       :state           @state/app-state})
            data (wire/write-transit-json env)]
        (when (a/offer! ch {:name "patch" :data data})
          (state/update-prior-tree! ch tree))))))

(defn broadcast!
  "Diff against every connection's prior tree and enqueue per-channel
   patch envelopes."
  ([] (broadcast! nil))
  ([resolves-intent]
   (doseq [ch (keys @state/connections)]
     (broadcast-to-channel! ch resolves-intent))))

;; ---------------------------------------------------------------------------
;; SSE

(defn- on-stream-ready
  "Called by Pedestal when an SSE connection is ready. Register the
   channel and push the initial frame through the regular broadcast
   path -- diff(nil, current) yields a full :replace at root."
  [event-ch ctx]
  (state/add-connection! event-ch)
  (broadcast-to-channel! event-ch nil)
  ctx)

;; ---------------------------------------------------------------------------
;; Intent endpoint

(defn- transit-response [status body]
  {:status  status
   :headers {"Content-Type" "application/transit+json"}
   :body    (wire/write-transit-json body)})

(defn- intent-handler [request]
  (let [{:keys [intent params id]} (:transit-params request)]
    (if-let [err (intents/validate-params intent params)]
      (transit-response 400 {:status :error :resolves-intent id :error err})
      (do
        (swap! state/app-state intents/apply-intent intent params)
        (broadcast! id)
        (transit-response 200 {:status :ok :resolves-intent id})))))

;; ---------------------------------------------------------------------------
;; Static files

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "map"  "application/json; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"
   "txt"  "text/plain; charset=utf-8"})

(defn- ext-of [^String name]
  (let [i (.lastIndexOf name ".")]
    (when (>= i 0) (.toLowerCase (subs name (inc i))))))

(defn- safe-resolve ^File [^File root ^String url-path]
  (let [trimmed   (subs url-path 1)
        candidate (.normalize (.toPath (File. root trimmed)))
        root-path (.normalize (.toPath root))]
    (when (.startsWith candidate root-path)
      (.toFile candidate))))

(defn- static-interceptor [^File root]
  (interceptor/interceptor
   {:name ::static
    :enter
    (fn [ctx]
      (if (:response ctx)
        ctx
        (let [{:keys [request-method uri]} (:request ctx)]
          (if-not (= :get request-method)
            ctx
            (let [path (if (or (nil? uri) (= "/" uri)) "/index.html" uri)]
              (if-let [^File f0 (safe-resolve root path)]
                (let [f (if (.isDirectory f0) (File. f0 "index.html") f0)]
                  (if (.isFile f)
                    (let [ct (or (mime-types (ext-of (.getName f)))
                                 "application/octet-stream")]
                      (assoc ctx :response
                             {:status  200
                              :headers {"Content-Type" ct}
                              :body    f}))
                    ctx))
                ctx))))))}))

;; ---------------------------------------------------------------------------
;; Routes

(def routes
  (route/expand-routes
   #{["/wun"    :get  (sse/start-event-stream on-stream-ready)
      :route-name :wun-stream]
     ["/intent" :post [(body-params/body-params) intent-handler]
      :route-name :wun-intent]}))

;; ---------------------------------------------------------------------------
;; Service lifecycle

(defn- existing-dir [^String path]
  (let [f (some-> path File. .getCanonicalFile)]
    (when (and f (.isDirectory f)) f)))

(defn service-map
  ([] (service-map {}))
  ([{:keys [port host]
     :or   {port 8080 host "0.0.0.0"}}]
   {::http/routes          routes
    ::http/type            :jetty
    ::http/port            port
    ::http/host            host
    ::http/join?           false
    ::http/secure-headers  nil
    ::http/allowed-origins {:creds true :allowed-origins (constantly true)}}))

(defn start!
  ([] (start! {}))
  ([{:keys [static] :as opts}]
   (let [resolved-static (existing-dir
                          (or static
                              (System/getenv "WUN_STATIC")
                              "../wun-web/public"))
         sm  (service-map opts)
         sm  (-> sm
                 http/default-interceptors
                 (cond-> resolved-static
                         (update ::http/interceptors
                                 #(conj (vec %) (static-interceptor resolved-static)))))]
     (when resolved-static
       (println (str "  serving static files from " (.getPath resolved-static))))
     (-> sm http/create-server http/start))))

(defn stop! [server]
  (when server (http/stop server)))
