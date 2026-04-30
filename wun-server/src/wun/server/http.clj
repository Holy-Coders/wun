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
            [clojure.tools.logging :as log]
            [io.pedestal.http      :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sse  :as sse]
            [io.pedestal.interceptor :as interceptor]
            [wun.capabilities      :as capabilities]
            [wun.diff              :as diff]
            [wun.intents           :as intents]
            [wun.screens           :as screens]
            [wun.server.state      :as state]
            [wun.server.wire       :as wire])
  (:import  [java.io File]
            [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; Tree + broadcast

(defn- current-tree []
  ;; TODO(phase 1.D-routing): pick screen by request path.
  (screens/render :counter/main @state/app-state))

(defn- broadcast-to-channel!
  "Diff `ch`'s prior tree against the per-connection-substituted current
   tree and enqueue a patch envelope iff there are patches or an
   intent to resolve. Substitution + encoding happen per-channel:
   different clients may advertise different caps and request
   different wire formats. Updates the stored prior on successful
   enqueue. On offer! failure, evicts the connection iff its channel
   has actually closed (so transient buffer-full conditions don't drop
   slow-but-alive clients)."
  [ch resolves-intent]
  (let [raw     (current-tree)
        caps    (state/caps ch)
        fmt     (state/fmt ch)
        tree    (capabilities/substitute raw caps)
        prior   (state/prior-tree ch)
        patches (diff/diff prior tree)]
    (when (or (seq patches) resolves-intent)
      (let [env  (wire/patch-envelope patches
                                      {:resolves-intent resolves-intent
                                       :state           @state/app-state})
            data (wire/encode-envelope fmt env)]
        (cond
          (a/offer! ch {:name "patch" :data data})
          (state/update-prior-tree! ch tree)

          (state/closed? ch)
          (do (state/remove-connection! ch)
              (log/debugf "wun: evicted closed connection (%d remain)"
                          (state/connection-count))))))))

(defn broadcast!
  "Diff against every connection's prior tree and enqueue per-channel
   patch envelopes."
  ([] (broadcast! nil))
  ([resolves-intent]
   (doseq [ch (keys @state/connections)]
     (broadcast-to-channel! ch resolves-intent))))

;; ---------------------------------------------------------------------------
;; SSE

(defn- parse-fmt
  "Pick a wire format for an SSE connection. EventSource can't set
   custom headers, so the format choice rides on a `?fmt=` query
   parameter. Web clients omit it (default :transit); native clients
   pass `?fmt=json`."
  [s]
  (case s
    "json"    :json
    "transit" :transit
    :transit))

(defn- on-stream-ready
  "Called by Pedestal when an SSE connection is ready. Reads the
   client's advertised capabilities from the `caps` query param and
   the wire format from the `fmt` query param (EventSource can't set
   custom headers; native clients in phase 2 use the
   `X-Wun-Capabilities` header instead). Registers the channel along
   with parsed metadata, then pushes the initial frame through the
   regular broadcast path -- diff(nil, current-substituted) yields a
   full :replace at root, with any unsupported subtrees already
   replaced by [:wun/WebFrame {...}]."
  [event-ch ctx]
  (let [params   (get-in ctx [:request :query-params])
        caps     (capabilities/parse (:caps params))
        fmt      (parse-fmt (:fmt params))]
    (state/add-connection! event-ch caps fmt)
    (log/debugf "wun: connected fmt=%s caps=%s" (name fmt) (pr-str caps))
    (broadcast-to-channel! event-ch nil)
    ctx))

;; ---------------------------------------------------------------------------
;; Intent endpoint

(defn- coerce-intent-keyword
  "JSON read-str leaves string values as strings; the intent name has
   to be a keyword to look up the morph. Transit preserves keywords,
   so this is a no-op for transit bodies."
  [v] (cond-> v (string? v) keyword))

(defn- request->envelope+fmt
  "Pull the intent envelope out of the parsed request. Pedestal's
   body-params interceptor splits transit-json into :transit-params
   and application/json into :json-params; the wire format we send
   back matches the request body's format."
  [request]
  (cond
    (:transit-params request)
    [(:transit-params request) :transit]

    (:json-params request)
    [(update (:json-params request) :intent coerce-intent-keyword) :json]))

(defn- response [fmt status body]
  {:status  status
   :headers {"Content-Type" (case fmt
                              :json "application/json; charset=utf-8"
                              "application/transit+json")}
   :body    (wire/encode-envelope fmt body)})

(defn- intent-handler [request]
  (let [[envelope fmt] (request->envelope+fmt request)
        {:keys [intent params id]} envelope]
    (if-let [err (intents/validate-params intent params)]
      (response fmt 400 {:status :error :resolves-intent id :error err})
      (do
        (swap! state/app-state intents/apply-intent intent params)
        (broadcast! id)
        (response fmt 200 {:status :ok :resolves-intent id})))))

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

;; ---------------------------------------------------------------------------
;; Per-connection GC. Pedestal closes the SSE event channel when a
;; client disconnects, but the entry in state/connections only goes
;; away when (a) a subsequent broadcast tries to offer! to it and
;; finds it closed, or (b) this scheduled sweep runs. Without (b),
;; a server with many short-lived connections under no broadcast
;; pressure leaks entries in the map.

(defonce ^:private ^ScheduledExecutorService gc-pool
  (Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r]
       (doto (Thread. ^Runnable r "wun-conn-gc")
         (.setDaemon true))))))

(defonce ^:private gc-handle (atom nil))

(defn- probe! [ch]
  ;; Push an SSE comment frame so Pedestal attempts a write and
  ;; surfaces dead sockets. Comments (": ...\n\n") are ignored by the
  ;; EventSource spec, so the client never sees them.
  (a/offer! ch {:data ":wun-probe"}))

(defn- gc-tick! []
  (let [before    (state/connection-count)
        ;; First, evict anything Pedestal has already closed.
        evicted-1 (state/evict-closed!)
        ;; Then probe what's left so the next tick can detect any
        ;; sockets that have died since the last write attempt.
        live      (keys @state/connections)
        _         (doseq [ch live] (probe! ch))
        after     (state/connection-count)]
    (log/debugf "wun: gc tick (before=%d evicted=%d after=%d)"
                before evicted-1 after)
    (when (pos? evicted-1)
      (log/infof "wun: gc'd %d closed connection(s) (%d remain)"
                 evicted-1 after))))

(defn- start-gc! [interval-secs]
  (when-let [h @gc-handle] (.cancel ^java.util.concurrent.ScheduledFuture h false))
  (reset! gc-handle
          (.scheduleAtFixedRate gc-pool
                                ^Runnable gc-tick!
                                ^long interval-secs
                                ^long interval-secs
                                TimeUnit/SECONDS)))

(defn- stop-gc! []
  (when-let [h @gc-handle]
    (.cancel ^java.util.concurrent.ScheduledFuture h false)
    (reset! gc-handle nil)))

(defn start!
  ([] (start! {}))
  ([{:keys [static gc-interval-secs] :or {gc-interval-secs 30} :as opts}]
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
     (start-gc! gc-interval-secs)
     (-> sm http/create-server http/start))))

(defn stop! [server]
  (stop-gc!)
  (when server (http/stop server)))
