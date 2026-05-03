(ns wun.server.upload
  "Streaming file uploads with progress patches.

   Wire model: client POSTs raw bytes to /upload with metadata in
   request headers. The server reads in chunks, writes to a
   configured storage directory, and updates the conn-state's
   `:uploads` map every `progress-interval-bytes` so the SSE stream
   pushes a fresh patch and the UI can re-render its progress bar.

   Why raw bytes instead of multipart: streaming progress falls out
   naturally. Pedestal's body-params multipart middleware buffers each
   part to a temp file before handing control back, which collapses
   the progress signal we want to surface.

   Required headers from the client:

     X-Wun-Conn-ID       conn-id from the current SSE handshake
     X-Wun-Upload-ID     client-generated UUID for this upload
     X-Wun-Filename      original filename (sanitised server-side)
     X-Wun-Size          total byte count (optional; enables percent)
     X-Wun-CSRF          CSRF token bound to this conn
     X-Wun-Form          (optional) form id this upload binds to
     X-Wun-Field         (optional) field name this upload binds to
     Content-Type        underlying mime type of the file

   Storage: writes go to `(upload-dir)` (configurable; defaults to
   the JVM tmpdir). Each completed upload is reachable at
   `/uploads/<upload-id>` via the static interceptor; production
   deploys typically swap that for a CDN / S3 backend by replacing
   the `commit-fn` in `config`."
  (:require [clojure.tools.logging :as log]
            [wun.server.csrf       :as csrf]
            [wun.server.state      :as state]
            [wun.server.telemetry  :as telemetry]
            [wun.uploads           :as uploads])
  (:import  [java.io File FileOutputStream InputStream IOException]
            [java.nio.file Paths]))

;; ---------------------------------------------------------------------------
;; Config

(def default-progress-interval-bytes
  "Emit a progress patch every N received bytes. 256KB strikes a
   balance: small enough to feel responsive, large enough that very
   small uploads don't burn wire round-trips on patches the user
   wouldn't notice anyway."
  (* 256 1024))

(def default-max-size-bytes
  "Reject uploads larger than this. 25MB is generous for most form
   fields and below the typical reverse-proxy default. Tune via
   `configure!`."
  (* 25 1024 1024))

(defonce config
  (atom {:upload-dir              nil ;; resolved lazily from env
         :progress-interval-bytes default-progress-interval-bytes
         :max-size-bytes          default-max-size-bytes
         :public-url-prefix       "/uploads/"
         :commit-fn               nil ;; (entry, file) -> public-url
         }))

(defn configure!
  "Override defaults. Apps that store uploads off-host (S3, R2)
   wire `:commit-fn` to upload the file there and return the public
   URL; `:upload-dir` is then just a staging dir."
  [m]
  (swap! config merge m))

(defn- upload-dir ^File []
  (or (some-> (:upload-dir @config) (File.))
      (some-> (System/getenv "WUN_UPLOAD_DIR") (File.))
      (let [tmp (System/getProperty "java.io.tmpdir")
            d   (File. ^String tmp "wun-uploads")]
        (.mkdirs d)
        d)))

;; ---------------------------------------------------------------------------
;; Streaming reader

(defn- parse-long-header [v]
  (when v (try (Long/parseLong v) (catch NumberFormatException _ nil))))

(defn- target-file ^File [upload-id filename]
  (let [dir (upload-dir)
        ;; Disambiguate filename collisions across uploads by prefixing
        ;; the upload-id; we can recover the original filename from the
        ;; saved entry if needed.
        safe (uploads/safe-filename filename)]
    (File. dir (str upload-id "-" safe))))

(defn- emit-progress!
  "Update the conn-state's upload entry and broadcast. `broadcast-fn`
   is `(conn-id) -> ()` and is the wun.server.http/broadcast-to-conn!
   indirection -- passing it in keeps this ns free of pedestal types."
  [conn-id upload-id received broadcast-fn]
  (state/swap-state-for! conn-id uploads/progress upload-id received)
  (when broadcast-fn (broadcast-fn conn-id))
  (telemetry/emit! :wun/upload.progress
                   {:conn-id conn-id :upload-id upload-id :received received}))

(defn- write-stream!
  "Pump bytes from `in` into `out`, calling `progress-fn` every
   `interval` bytes. Returns the total bytes written. Closes neither
   stream -- the caller wraps with try/finally."
  [^InputStream in ^FileOutputStream out interval max-size progress-fn]
  (let [buf (byte-array (* 16 1024))]
    (loop [total 0
           since-last 0]
      (let [n (.read in buf)]
        (cond
          (neg? n)
          total

          (> (+ total n) max-size)
          (throw (ex-info "upload exceeds max size"
                          {:reason :too-large :total (+ total n) :max max-size}))

          :else
          (do (.write out buf 0 n)
              (let [total' (+ total n)
                    since' (+ since-last n)]
                (if (>= since' interval)
                  (do (progress-fn total')
                      (recur total' 0))
                  (recur total' since')))))))))

;; ---------------------------------------------------------------------------
;; Entry-point fn called by the Pedestal handler. Pure-ish: takes the
;; request map, returns a result map. Side effects are confined to:
;; (a) writing the file under `upload-dir`, (b) mutating
;; `conn-states[conn-id]`, and (c) calling `broadcast-fn` to push
;; patches. No Pedestal types referenced.

(defn handle-upload!
  "Drive an upload from the request map. Returns a response map.
   `broadcast-fn` is `(conn-id) -> ()`; the caller threads in the
   wun.server.http broadcast helper."
  [request broadcast-fn]
  (let [headers (:headers request)
        cid     (get headers "x-wun-conn-id")
        uid     (get headers "x-wun-upload-id")
        size    (parse-long-header (get headers "x-wun-size"))
        csrf-tok (or (get headers "x-wun-csrf") (get headers "X-Wun-CSRF"))
        form     (get headers "x-wun-form")
        field    (get headers "x-wun-field")
        filename (or (get headers "x-wun-filename") "upload.bin")
        ctype    (or (get headers "content-type") "application/octet-stream")
        cfg      @config]
    (cond
      (or (nil? cid) (nil? uid))
      {:status 400 :body "missing X-Wun-Conn-ID or X-Wun-Upload-ID"}

      (and (csrf/required?) (not (csrf/valid? cid csrf-tok)))
      (do (telemetry/emit! :wun/csrf.miss {:conn-id cid :reason :upload})
          {:status 403 :body "csrf-invalid"})

      :else
      (let [body ^InputStream (:body request)
            file (target-file uid filename)
            entry (uploads/empty-upload uid {:form         (some-> form keyword)
                                             :field        (some-> field keyword)
                                             :filename     filename
                                             :content-type ctype
                                             :size         size})]
        (state/swap-state-for! cid uploads/assoc-upload uid entry)
        (state/swap-state-for! cid uploads/start uid)
        (when broadcast-fn (broadcast-fn cid))
        (telemetry/emit! :wun/upload.start
                         {:conn-id cid :upload-id uid :size size})
        (try
          (with-open [out (FileOutputStream. file)]
            (let [received (write-stream! body out
                                          (:progress-interval-bytes cfg)
                                          (:max-size-bytes cfg)
                                          (fn [n] (emit-progress! cid uid n broadcast-fn)))
                  url (if-let [f (:commit-fn cfg)]
                        (f (assoc entry :received received) file)
                        (str (:public-url-prefix cfg)
                             (.getName file)))]
              (state/swap-state-for! cid uploads/complete uid url)
              (when broadcast-fn (broadcast-fn cid))
              (telemetry/emit! :wun/upload.complete
                               {:conn-id cid :upload-id uid})
              {:status  200
               :headers {"Content-Type" "application/json"}
               :body    (str "{\"status\":\"ok\",\"upload-id\":\""
                             uid "\",\"url\":\"" url "\",\"received\":" received "}")}))
          (catch Throwable t
            (let [reason (or (some-> t ex-data :reason) :io)]
              (state/swap-state-for! cid uploads/errored uid reason)
              (when broadcast-fn (broadcast-fn cid))
              (telemetry/emit! :wun/upload.error
                               {:conn-id cid :upload-id uid :reason reason})
              (try (.delete file) (catch Throwable _ nil))
              (log/warnf t "wun.upload: failed (cid=%s uid=%s)" cid uid)
              {:status (case reason :too-large 413 500)
               :body   (str "{\"status\":\"error\",\"reason\":\"" (name reason) "\"}")
               :headers {"Content-Type" "application/json"}})))))))
