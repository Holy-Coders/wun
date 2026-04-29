(ns wun.server.wire
  "Wire format helpers. Phase 1.B onward, patches come from wun.diff;
   this namespace is just transit serialisation + envelope construction."
  (:require [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn write-transit-json [v]
  (let [out (ByteArrayOutputStream. 1024)
        w   (transit/writer out :json)]
    (transit/write w v)
    (.toString out "UTF-8")))

(defn read-transit-json [s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))
        r  (transit/reader in :json)]
    (transit/read r)))

(defn patch-envelope
  "Build the SSE envelope: a (possibly empty) `:patches` vector and
   `:status :ok`, plus optional `extras` keys:

     :resolves-intent  the UUID of the intent this envelope confirms
     :state            current screen state, mirrored client-side so
                       optimistic morphs can predict against the same
                       value the server saw"
  ([patches] (patch-envelope patches nil))
  ([patches extras]
   (merge {:patches (vec patches) :status :ok}
          (some-> extras (select-keys [:resolves-intent :state])))))
