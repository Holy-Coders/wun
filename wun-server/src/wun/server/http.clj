(ns wun.server.http
  "Phase 0 HTTP transport.

   Uses the JDK's built-in com.sun.net.httpserver.HttpServer because the
   sandbox running this spike has no access to Clojars. The interceptor
   chain, Pedestal SSE, and content-negotiation infrastructure called for
   in the brief land in phase 1 once Clojars is reachable -- only the
   transport changes; the wire format, intent semantics, and component
   vocabulary stay identical.

   Threading model: one OS thread per SSE connection blocked on a
   per-connection core.async channel. Acceptable for a phase-0 demo; phase
   1 swaps to Pedestal/Jetty NIO."
  (:require [clojure.core.async :as a]
            [wun.intents        :as intents]
            [wun.screens        :as screens]
            [wun.server.state   :as state]
            [wun.server.wire    :as wire])
  (:import  [com.sun.net.httpserver HttpServer HttpExchange HttpHandler]
            [java.net InetSocketAddress]
            [java.io File OutputStream IOException]
            [java.nio.charset StandardCharsets]
            [java.nio.file Files]
            [java.util.concurrent Executors]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- add-cors! [^HttpExchange ex]
  (let [h (.getResponseHeaders ex)]
    (.add h "Access-Control-Allow-Origin"  "*")
    (.add h "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
    (.add h "Access-Control-Allow-Headers" "Content-Type")))

(defn- write-empty! [^HttpExchange ex status]
  (add-cors! ex)
  (.sendResponseHeaders ex status -1)
  (.close ex))

(defn- write-bytes! [^HttpExchange ex status content-type ^bytes body]
  (add-cors! ex)
  (.add (.getResponseHeaders ex) "Content-Type" content-type)
  (.sendResponseHeaders ex status (alength body))
  (with-open [^OutputStream out (.getResponseBody ex)]
    (.write out body)))

;; ---------------------------------------------------------------------------
;; SSE patch stream.
;;
;; Each connection gets its own core.async channel acting as the outbound
;; queue. The handler thread blocks on the channel and writes SSE frames to
;; the client until the channel closes or the socket dies.

(defn- emit-frame! [^OutputStream out envelope]
  (let [data (wire/write-transit-json envelope)
        bs   (utf8 (str "event: patch\n"
                        "data: "  data "\n\n"))]
    (.write out bs)
    (.flush out)))

(defn- handle-sse [^HttpExchange ex]
  (when (= "OPTIONS" (.getRequestMethod ex))
    (write-empty! ex 204))
  (let [h (.getResponseHeaders ex)]
    (add-cors! ex)
    (.add h "Content-Type"     "text/event-stream")
    (.add h "Cache-Control"    "no-cache")
    (.add h "Connection"       "keep-alive")
    (.add h "X-Accel-Buffering" "no"))
  (.sendResponseHeaders ex 200 0)
  (let [conn-ch (a/chan 32)
        out    (.getResponseBody ex)]
    (state/add-connection! conn-ch)
    (try
      ;; Kick the connection by emitting the current tree.
      (emit-frame! out (wire/replace-root-envelope
                        (screens/render :counter/main @state/app-state)))
      ;; Drain envelopes until disconnect.
      (loop []
        (when-let [env (a/<!! conn-ch)]
          (emit-frame! out env)
          (recur)))
      (catch IOException _ nil)         ; client closed socket
      (finally
        (state/remove-connection! conn-ch)
        (a/close! conn-ch)
        (try (.close out) (catch Exception _))
        (.close ex)))))

(defn broadcast!
  "Re-render the tree and enqueue an envelope on every connection's queue.
   Returns the number of channels written to."
  ([] (broadcast! nil))
  ([resolves-intent]
   (let [env (wire/replace-root-envelope
              (screens/render :counter/main @state/app-state)
              resolves-intent)]
     (reduce (fn [n ch]
               (if (a/offer! ch env)
                 (inc n)
                 n))
             0
             @state/connections))))

;; ---------------------------------------------------------------------------
;; Intent endpoint.

(defn- handle-intent [^HttpExchange ex]
  (let [method (.getRequestMethod ex)]
    (cond
      (= "OPTIONS" method)
      (write-empty! ex 204)

      (= "POST" method)
      (let [raw      (slurp (.getRequestBody ex) :encoding "UTF-8")
            envelope (wire/read-transit-json raw)
            {:keys [intent params id]} envelope
            before   @state/app-state
            after    (intents/apply-intent before intent params)]
        (when (not= before after)
          (reset! state/app-state after))
        (broadcast! id)
        (write-bytes! ex 200 "application/transit+json"
                      (utf8 (wire/write-transit-json
                             {:status :ok :resolves-intent id}))))

      :else
      (write-empty! ex 405))))

;; ---------------------------------------------------------------------------
;; Static file serving. Optional; the spike serves wun-web's public/ from
;; the same process so the user only runs one command. Phase 1 will give
;; the web client its own dev server again.

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "map"  "application/json; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"})

(defn- ext [^String name]
  (let [i (.lastIndexOf name ".")]
    (when (pos? i) (.toLowerCase (subs name (inc i))))))

(defn- safe-resolve ^File [^File root ^String url-path]
  (let [trimmed   (subs url-path 1)
        candidate (.normalize (.toPath (File. root trimmed)))
        root-path (.normalize (.toPath root))]
    (when (.startsWith candidate root-path)
      (.toFile candidate))))

(defn- handle-static [^File root ^HttpExchange ex]
  (let [raw-path (.getPath (.getRequestURI ex))
        path     (if (= "/" raw-path) "/index.html" raw-path)]
    (if-let [^File f (safe-resolve root path)]
      (let [f (if (.isDirectory f) (File. f "index.html") f)]
        (if (.isFile f)
          (let [ct (or (mime-types (ext (.getName f))) "application/octet-stream")
                bs (Files/readAllBytes (.toPath f))]
            (write-bytes! ex 200 ct bs))
          (write-empty! ex 404)))
      (write-empty! ex 404))))

;; ---------------------------------------------------------------------------
;; Server lifecycle.

(defn- handler ^HttpHandler [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try (f exchange)
           (catch Throwable t
             (println "wun: handler error:" (.getMessage t))
             (.printStackTrace t)
             (try (.close exchange) (catch Exception _)))))))

(defn- resolve-static-root ^File [path]
  (let [f (.getCanonicalFile (File. ^String path))]
    (when (.isDirectory f) f)))

(defn start!
  "Start the HTTP server. Returns the running server.

   Options:
     :port          default 8080
     :host          default 0.0.0.0
     :backlog       default 0
     :static        path to a directory of static files served at `/`.
                    Defaults to env var WUN_STATIC if set, otherwise
                    \"../wun-web/public\" relative to cwd. Pass nil to
                    disable static serving."
  ([] (start! {}))
  ([{:keys [port host backlog static]
     :or   {port 8080 host "0.0.0.0" backlog 0
            static (or (System/getenv "WUN_STATIC") "../wun-web/public")}}]
   (let [srv  (HttpServer/create (InetSocketAddress. ^String host ^int port) backlog)
         root (when static (resolve-static-root static))]
     (.createContext srv "/wun"    (handler handle-sse))
     (.createContext srv "/intent" (handler handle-intent))
     (when root
       (.createContext srv "/" (handler #(handle-static root %)))
       (println (str "  serving static files from " (.getPath root))))
     (.setExecutor srv (Executors/newCachedThreadPool))
     (.start srv)
     srv)))

(defn stop! [^HttpServer srv]
  (when srv (.stop srv 0)))
