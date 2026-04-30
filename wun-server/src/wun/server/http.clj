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
            [wun.server.html       :as wun-html]
            [wun.server.state      :as state]
            [wun.server.wire       :as wire])
  (:import  [java.io File]
            [java.net URLEncoder]
            [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; Tree + broadcast

(defn- current-tree-for [screen-key]
  (screens/render screen-key @state/app-state))

(defn- web-frame-src
  "Build a relative URL the iOS / Android client can navigate to in a
   WebFrame to render the component the native client lacks. Token
   is content-addressed (hex of the subtree's hash) so identical
   subtrees produce identical tokens -- otherwise diff against the
   prior tree would emit a fresh `:replace` for every broadcast as
   the URL churned. Stashes the subtree under that token so the
   /web-frames endpoint can re-render it; URL is
   /web-frames/<urlencoded-key>/<token>."
  [component-key tree]
  (let [piece (str (namespace component-key) "/" (name component-key))
        token (Long/toHexString (Math/abs (long (hash tree))))]
    (state/stash-webframe! token tree)
    (str "/web-frames/" (URLEncoder/encode piece "UTF-8") "/" token)))

(defn- broadcast-to-channel!
  "Diff `ch`'s prior tree against the per-connection-substituted current
   tree and enqueue a patch envelope iff there are patches, an intent
   to resolve, or `extra-keys` (e.g. screen-stack/conn-id changes the
   client needs even when the rendered tree is unchanged).

   Substitution + encoding happen per-channel: different clients may
   advertise different caps and request different wire formats.
   Updates the stored prior + prior-meta on successful enqueue. On
   offer! failure, evicts the connection iff its channel has
   actually closed (so transient buffer-full conditions don't drop
   slow-but-alive clients).

   Meta (page title, description, theme-color, etc.) is computed
   per-screen via the screen's optional `:meta` fn and only included
   when it differs from what this connection last saw -- no wire
   noise for an unchanged title."
  ([ch resolves-intent] (broadcast-to-channel! ch resolves-intent nil))
  ([ch resolves-intent extra-keys]
   (let [screen-key  (state/screen-key ch)
         raw         (current-tree-for screen-key)
         caps        (state/caps ch)
         fmt         (state/fmt ch)
         tree        (capabilities/substitute raw caps web-frame-src)
         prior       (state/prior-tree ch)
         patches     (diff/diff prior tree)
         meta        (screens/render-meta screen-key @state/app-state)
         prior-meta  (state/prior-meta ch)
         meta-extra  (when (and meta (not= meta prior-meta)) {:meta meta})]
     (when (or (seq patches) resolves-intent (seq extra-keys) meta-extra)
       (let [env  (wire/patch-envelope patches
                                       (merge {:resolves-intent resolves-intent
                                               :state           @state/app-state}
                                              meta-extra
                                              extra-keys))
             data (wire/encode-envelope fmt env)]
         (cond
           (a/offer! ch {:name "patch" :data data})
           (do (state/update-prior-tree! ch tree)
               (when meta-extra (state/update-prior-meta! ch meta)))

           (state/closed? ch)
           (do (state/remove-connection! ch)
               (log/debugf "wun: evicted closed connection (%d remain)"
                           (state/connection-count)))))))))

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

(defn- resolve-screen-key
  "Pick the screen to bind this SSE connection to. Honours an
   explicit `?path=` query param first (so a multi-screen
   connection-per-screen model works), then `?screen=` for clients
   that prefer to address by screen key, then defaults to the
   screen registered at `/`. Falls back to `:counter/main` if no
   screen has the `/` path -- belt-and-braces for early bring-up."
  [params]
  (or (when-let [p (:path params)] (screens/lookup-by-path p))
      (when-let [s (:screen params)] (keyword s))
      (screens/lookup-by-path "/")
      :counter/main))

(defn- on-stream-ready
  "Called by Pedestal when an SSE connection is ready. Reads the
   client's advertised capabilities and wire format from either
   `X-Wun-Capabilities` / `X-Wun-Format` request headers (preferred,
   used by native clients) or `?caps=` / `?fmt=` query params (web
   fallback because EventSource can't set custom headers). Also reads
   `?path=` to bind this connection to a particular screen.

   Registers the channel along with parsed metadata and a fresh
   server-assigned conn-id, then pushes the initial frame through the
   regular broadcast path. The first envelope carries the conn-id and
   the initial screen-stack so the client can echo conn-id on /intent
   POSTs and route framework intents (`:wun/navigate`, `:wun/pop`)
   back to the originating connection."
  [event-ch ctx]
  (let [request    (:request ctx)
        headers    (:headers request)
        params     (:query-params request)
        caps-str   (or (get headers "x-wun-capabilities") (:caps params))
        fmt-str    (or (get headers "x-wun-format")       (:fmt params))
        caps       (capabilities/parse caps-str)
        fmt        (parse-fmt fmt-str)
        screen-key (resolve-screen-key params)
        conn-id    (str (java.util.UUID/randomUUID))]
    (state/add-connection! event-ch caps fmt screen-key conn-id)
    (log/debugf "wun: connected conn-id=%s screen=%s fmt=%s caps=%s"
                conn-id screen-key (name fmt) (pr-str caps))
    (broadcast-to-channel! event-ch nil
                           {:conn-id      conn-id
                            :screen-stack [screen-key]})
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

;; Framework intents are routed by the server itself rather than going
;; through the `intents/registry` morph dispatch. Their effect is
;; per-connection (push / pop / replace the screen on the connection's
;; stack), so they need a conn-id from the client and never broadcast
;; to other connections. Splitting them out keeps app-defined intents
;; pure morphs of app state, the way the brief calls them.

(def ^:private framework-intents
  #{:wun/navigate :wun/pop :wun/replace})

(defn- resolve-screen-from-params
  "Framework navigation params can carry either `:screen` (an explicit
   keyword like `:about/main`) or `:path` (a literal route path like
   `/about`). Returns the keyword to push, or nil when neither
   resolves to a registered screen."
  [{:keys [screen path]}]
  (or (when screen
        (let [k (cond-> screen (string? screen) keyword)]
          (when (screens/lookup k) k)))
      (when path (screens/lookup-by-path path))))

(defn- handle-framework-intent!
  "Apply a framework intent against the originating connection only.
   Returns a map suitable for splatting into the response envelope:
   `{:status :ok|:error :error <map>}`. Also broadcasts the new tree
   for that connection (everyone else's screen-stack is untouched)."
  [ch intent params id]
  (cond
    (nil? ch)
    {:status :error
     :error  {:reason  :unknown-conn
              :message "framework intent missing or unknown :conn-id"}}

    (= intent :wun/navigate)
    (if-let [target (resolve-screen-from-params params)]
      (let [stack (state/push-screen! ch target)]
        (broadcast-to-channel! ch id {:screen-stack stack})
        {:status :ok})
      {:status :error
       :error  {:reason  :no-such-screen
                :message "no screen matched :screen or :path"
                :params  params}})

    (= intent :wun/replace)
    (if-let [target (resolve-screen-from-params params)]
      (let [stack (state/replace-screen! ch target)]
        (broadcast-to-channel! ch id {:screen-stack stack})
        {:status :ok})
      {:status :error
       :error  {:reason  :no-such-screen
                :message "no screen matched :screen or :path"
                :params  params}})

    (= intent :wun/pop)
    (let [stack (state/pop-screen! ch)]
      (broadcast-to-channel! ch id {:screen-stack stack})
      {:status :ok})))

(defn- coerce-conn-id
  "JSON envelopes encode UUIDs as strings already; transit ones may
   send a real UUID. Either way we store them as strings on the
   connection so equality is straightforward."
  [v]
  (cond
    (nil? v)    nil
    (string? v) v
    :else       (str v)))

(defn- intent-handler [request]
  (let [[envelope fmt]            (request->envelope+fmt request)
        {:keys [intent params id]} envelope
        conn-id                    (coerce-conn-id (:conn-id envelope))]
    (cond
      (contains? framework-intents intent)
      (let [ch     (some-> conn-id state/channel-by-conn-id)
            result (handle-framework-intent! ch intent params id)]
        (case (:status result)
          :ok    (response fmt 200 {:status :ok :resolves-intent id})
          :error (response fmt 400 {:status :error :resolves-intent id
                                    :error  (:error result)})))

      :else
      (if-let [err (intents/validate-params intent params)]
        (response fmt 400 {:status :error :resolves-intent id :error err})
        (do
          (swap! state/app-state intents/apply-intent intent params)
          (broadcast! id)
          (response fmt 200 {:status :ok :resolves-intent id}))))))

;; ---------------------------------------------------------------------------
;; WebFrame fallback endpoint.
;;
;; When capability negotiation collapses an unsupported subtree to
;; [:wun/WebFrame {:src "/web-frames/<key>" :missing <kw>}], the
;; native client navigates to that URL inside a WKWebView (Hotwire
;; Native on iOS in 2.F+) and the server replies with HTML the user
;; sees in place of the missing native rendering.
;;
;; Phase 2.F ships a stub HTML page acknowledging the fallback. A
;; later slice can render the actual subtree via the same web cljs
;; renderers, parameterised by component key, so the WebFrame is a
;; pixel-perfect facsimile of what the web client would draw.

(defn- web-frame-handler [request]
  (let [k     (get-in request [:path-params :key])
        token (get-in request [:path-params :token])
        tree  (state/webframe-tree token)]
    (cond
      (nil? token)
      ;; Legacy /web-frames/<key> with no token (older clients) still
      ;; gets a stub explanation rather than a 404.
      {:status  200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (str "<!doctype html><html><body style='font-family:system-ui;padding:24px;'>"
                     "<h1>WebFrame fallback for <code>:" (or k "") "</code></h1>"
                     "<p>No subtree token in URL.</p></body></html>")}

      (nil? tree)
      {:status  410
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (str "<!doctype html><html><body style='font-family:system-ui;padding:24px;'>"
                     "<h1>WebFrame token expired</h1>"
                     "<p>The server has evicted the cached subtree for token <code>"
                     token "</code>. Reconnect to repopulate.</p></body></html>")}

      :else
      {:status  200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (wun-html/render-document k tree)})))

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

(defn- send-file [^File f]
  (let [ct (or (mime-types (ext-of (.getName f))) "application/octet-stream")]
    {:status  200
     :headers {"Content-Type"  ct
               ;; Spike-time: every request revalidates so cljs
               ;; rebuilds show up without forcing the user to
               ;; hard-refresh.
               "Cache-Control" "no-cache, no-store, must-revalidate"
               "Pragma"        "no-cache"
               "Expires"       "0"}
     :body    f}))

;; Paths the server handles itself and that must not be hijacked by
;; the SPA fallback. Without this, a request to /web-frames/<k>/<bad>
;; (which Pedestal's router transforms before our interceptor sees
;; it) ends up back here with no :response set and `looks-like-route?`
;; would happily serve index.html instead of the real 410.
(def ^:private server-handled-prefixes
  ["/wun" "/intent" "/web-frames"])

(defn- server-handled? [^String uri]
  (some #(.startsWith uri ^String %) server-handled-prefixes))

(defn- looks-like-route?
  "Treat a path as a client-side route (eligible for SPA fallback to
   index.html) when it has no file extension AND isn't claimed by a
   server endpoint. `.js`, `.css`, `.map` etc. still 404 properly so
   a typo doesn't masquerade as the SPA shell."
  [^String uri]
  (and (nil? (ext-of uri))
       (not (server-handled? uri))))

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
            (let [path (if (or (nil? uri) (= "/" uri)) "/index.html" uri)
                  f0   (safe-resolve root path)
                  f    (cond
                         (and f0 (.isDirectory f0)) (File. f0 "index.html")
                         f0                         f0)]
              (cond
                (and f (.isFile f))
                (assoc ctx :response (send-file f))

                ;; SPA fallback: a path the browser is *navigating* to
                ;; (no file extension) that doesn't exist on disk gets
                ;; index.html so client-side routing -- including a
                ;; reload of `/about` -- always lands on the bundle,
                ;; which then asks the server for the right screen.
                (and (looks-like-route? uri)
                     (not= "/index.html" path))
                (let [^File index (File. root "index.html")]
                  (if (.isFile index)
                    (assoc ctx :response (send-file index))
                    ctx))

                :else ctx))))))}))

;; ---------------------------------------------------------------------------
;; Routes

(def routes
  (route/expand-routes
   #{["/wun"    :get  (sse/start-event-stream on-stream-ready)
      :route-name :wun-stream]
     ["/intent" :post [(body-params/body-params) intent-handler]
      :route-name :wun-intent]
     ["/web-frames/:key"        :get web-frame-handler :route-name :wun-web-frame]
     ["/web-frames/:key/:token" :get web-frame-handler :route-name :wun-web-frame-token]}))

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
