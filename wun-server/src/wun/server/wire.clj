(ns wun.server.wire
  "Wire format helpers. Phase 2 introduces JSON for native clients
   alongside transit-json for the web. Patches still come from
   wun.diff; this namespace is just (de)serialisation + envelope
   construction.

   Wire shape on JSON: keywords are encoded as namespaced strings
   (`:wun/Stack` -> `\"wun/Stack\"`), UUIDs as strings, the rest of
   Clojure data structures map naturally onto JSON. The shape of the
   envelope and the Hiccup tree is identical to the transit version;
   only the encoding differs."
  (:require [cognitect.transit :as transit]
            [clojure.data.json :as json]
            [clojure.walk      :as walk])
  (:import  [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; ---------------------------------------------------------------------------
;; Transit

(defn write-transit-json [v]
  (let [out (ByteArrayOutputStream. 1024)
        w   (transit/writer out :json)]
    (transit/write w v)
    (.toString out "UTF-8")))

(defn read-transit-json [s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))
        r  (transit/reader in :json)]
    (transit/read r)))

;; ---------------------------------------------------------------------------
;; JSON

(defn- kw->str [k]
  (if-let [ns- (namespace k)]
    (str ns- "/" (name k))
    (name k)))

(defn- prepare-for-json
  "Walk the structure and convert keywords + UUIDs to strings, so
   data.json's encoder sees only natively-supported types. Necessary
   because data.json's `:value-fn` callback only fires for *map*
   values, not for keywords sitting inside vectors (which is exactly
   where Hiccup component tags live)."
  [v]
  (walk/postwalk
   (fn [x]
     (cond
       (keyword? x) (kw->str x)
       (uuid? x)    (str x)
       :else        x))
   v))

(defn write-json [v]
  (json/write-str (prepare-for-json v)))

(defn read-json [s]
  ;; Keys come back as keywords (`\"foo/bar\"` -> `:foo/bar`); values
  ;; stay as strings -- callers convert known-keyword fields like
  ;; `:intent` themselves.
  (json/read-str s :key-fn keyword))

;; ---------------------------------------------------------------------------
;; Envelopes

(def envelope-version
  "Current wire envelope version. Phase 2 ships v2 (key-aware list
   diffing via the `:children` op). Servers serve clients at the
   version they negotiate at connect (header `X-Wun-Envelope` / query
   `?envelope=`); absent negotiation, the server defaults to the
   current version. Clients compare the version on receive and either
   downgrade rendering or refuse to apply the envelope."
  2)

(def supported-envelope-versions
  "Versions a server is willing to serve. v1 stays supported because
   shipped iOS/Android binaries on App-Store-style channels can't be
   upgraded synchronously with the server."
  #{1 2})

(defn negotiate-version
  "Pick the wire version to use for this connection. `requested` may
   be nil (use server default), an integer, or an integer-shaped
   string. Falls back to the highest version both sides support."
  [requested]
  (let [r (cond
            (nil? requested)    nil
            (integer? requested) requested
            :else                (try (Integer/parseInt (str requested))
                                     (catch Exception _ nil)))]
    (cond
      (nil? r)                       envelope-version
      (contains? supported-envelope-versions r) r
      :else                          envelope-version)))

(defn patch-envelope
  "Build the SSE envelope: a (possibly empty) `:patches` vector and
   `:status :ok`, plus optional `extras` keys:

     :envelope-version always present; defaults to current
     :resolves-intent  the UUID of the intent this envelope confirms
     :state            current screen state, mirrored client-side so
                       optimistic morphs can predict against the same
                       value the server saw
     :conn-id          a server-assigned id the client echoes on
                       /intent POSTs so the server can route framework
                       intents (navigate / pop) to the right connection
     :screen-stack     vector of screen keys for this connection; top
                       is the currently-rendered screen. Updated when
                       the client (or a server-side rule) pushes / pops
     :presentations    per-screen presentation hint (`:push` / `:modal`)
     :meta             page-level metadata (title / description / etc.)
     :csrf-token       on first connect, the CSRF token bound to this
                       session; the client echoes it on /intent POSTs
     :resync?          true when this envelope is a backpressure-driven
                       full re-render rather than an incremental patch"
  ([patches] (patch-envelope patches nil))
  ([patches extras]
   (merge {:envelope-version (or (:envelope-version extras) envelope-version)
           :patches          (vec patches)
           :status           :ok}
          (some-> extras (select-keys [:resolves-intent :state
                                       :conn-id :screen-stack
                                       :presentations
                                       :meta
                                       :csrf-token
                                       :resync?
                                       :theme])))))

(defn encode-envelope
  "Encode an envelope using the given wire `fmt` (`:transit` or `:json`)."
  [fmt envelope]
  (case fmt
    :json    (write-json envelope)
    (write-transit-json envelope)))
