(ns wun.server.wire
  "Wire format helpers. Phase 0 only emits full-tree :replace patches at root;
   future phases will compute structural diffs against a per-connection
   memoized prior tree."
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

(defn replace-root-envelope
  "Build a patch envelope that replaces the entire UI tree at root.
   Optionally tags the envelope with the intent UUID it resolves."
  ([tree] (replace-root-envelope tree nil))
  ([tree resolves-intent]
   (cond-> {:patches [{:op :replace :path [] :value tree}]
            :status  :ok}
     resolves-intent (assoc :resolves-intent resolves-intent))))
