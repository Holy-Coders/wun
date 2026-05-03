(ns wun.server.session
  "Session token rotation + revocation registry.

   Wun's session token is opaque to the framework -- the per-app
   `register-init-state-fn!` resolves it against the app's auth table.
   The framework's contribution is two operations any auth system can
   layer on:

     rotate     -- given a current session token, issue a fresh one,
                   add the old one to a short-TTL revocation set so
                   in-flight reconnects with the old token are
                   rejected with a clean 401, and call the registered
                   rotation handler so the app can persist the new
                   token in its users table.

     revoke     -- given a token, add it to the revocation set. Used
                   on logout. The set is in-memory and bounded; apps
                   that need durable revocation across restarts /
                   replicas plug a backing store via `set-store!`.

   The revocation set is a TTL map: tokens are dropped after
   `revoke-ttl-ms` so the set doesn't grow forever. The TTL is set to
   the longest plausible reconnect window for an in-flight client; the
   token is also (typically) invalidated by the app's auth table, so
   the in-memory set is belt-and-braces for the gap between rotate and
   the app's DB write being visible to all replicas."
  (:require [wun.server.telemetry :as telemetry])
  (:import  [java.security SecureRandom]
            [java.util Base64]))

(def default-revoke-ttl-ms (* 5 60 1000)) ;; 5 minutes

;; ---------------------------------------------------------------------------
;; State

(defonce ^:private revoked
  ;; Map of token -> expires-at-ms
  (atom {}))

(defonce ^:private store
  (atom {:read  (fn [token] (some-> @revoked (get token)))
         :write (fn [token expires-at]
                  (swap! revoked assoc token expires-at))
         :delete (fn [token]
                   (swap! revoked dissoc token))}))

(defn set-store!
  "Plug a custom backing store. `s` is a map with :read / :write /
   :delete functions matching the in-memory default. Apps that need
   durable revocation across restarts wire Redis or a DB table here."
  [s]
  (reset! store s))

(defn reset-state!
  "Clear in-memory revocation set. Tests use this between cases."
  []
  (reset! revoked {})
  (reset! store {:read   (fn [token]
                           (some-> @revoked (get token)))
                 :write  (fn [token expires-at]
                           (swap! revoked assoc token expires-at))
                 :delete (fn [token]
                           (swap! revoked dissoc token))}))

;; ---------------------------------------------------------------------------
;; Rotation handler

(defonce ^:private rotation-handler (atom nil))

(defn register-rotation-handler!
  "Register `f` as `(f old-token new-token) -> nil`. Called inside
   `rotate!` after the new token is issued and the old one is
   revoked. Apps use this to update their users table."
  [f]
  (reset! rotation-handler f))

;; ---------------------------------------------------------------------------
;; Token issuance

(defn- random-bytes [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn issue-token
  "Mint a fresh session token: 32 random bytes, base64url-encoded
   without padding. Cryptographically random."
  []
  (b64url (random-bytes 32)))

;; ---------------------------------------------------------------------------
;; Operations

(defn revoked?
  "True when `token` is in the revocation set and not yet expired.
   Lazily evicts expired tokens on read so the set self-trims."
  ([token] (revoked? token (System/currentTimeMillis)))
  ([token now]
   (let [{:keys [read delete]} @store
         expires-at (read token)]
     (cond
       (nil? expires-at) false
       (> now expires-at) (do (delete token) false)
       :else true))))

(defn revoke!
  "Add `token` to the revocation set with a TTL. Idempotent."
  ([token] (revoke! token (System/currentTimeMillis) default-revoke-ttl-ms))
  ([token now ttl-ms]
   (let [{:keys [write]} @store]
     (write token (+ now ttl-ms)))))

(defn rotate!
  "Atomically: revoke `old-token`, issue a new token, run the
   rotation handler. Returns the new token. Used by app-level logout-
   then-relog flows and by middleware that rotates tokens on
   privileged actions to mitigate session fixation."
  [old-token]
  (let [new-token (issue-token)]
    (revoke! old-token)
    (when-let [f @rotation-handler]
      (try (f old-token new-token)
           (catch Throwable t
             (telemetry/emit! :wun/disconnect
                              {:reason :rotation-handler-failed
                               :message (.getMessage t)}))))
    new-token))

(defn purge-expired!
  "Drop all entries whose expires-at is <= `now`. Returns the number
   of entries dropped. Call from the same scheduled pool that does
   connection GC."
  ([] (purge-expired! (System/currentTimeMillis)))
  ([now]
   (let [old @revoked
         live (into {} (remove (fn [[_ exp]] (<= exp now)) old))]
     (when (not= (count old) (count live))
       (reset! revoked live))
     (- (count old) (count live)))))
