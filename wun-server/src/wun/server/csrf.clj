(ns wun.server.csrf
  "CSRF token issuance and validation for the /intent endpoint.

   The wire model: at SSE handshake the server issues an opaque CSRF
   token bound to the connection's session token (or to the conn-id
   when there is no session token yet). The client echoes the token
   on every /intent POST as either the `X-Wun-CSRF` header or the
   envelope's `:csrf-token` field. The server validates by recomputing
   the HMAC and comparing in constant time.

   Why not a pure same-origin policy: SSE survives a tab being kept
   open across a malicious page load, so CORS alone doesn't protect
   the intent endpoint. The token is necessary even when SameSite
   cookies are in play because Wun doesn't use cookies as the auth
   primitive (the session token is the primitive).

   Server secret: pulled from `WUN_CSRF_SECRET` env var. If absent, a
   random 32-byte secret is generated at first call and a warning is
   logged -- this is fine for development but will rotate on every
   server restart, invalidating outstanding tokens. Production deploys
   *must* set the env var to a stable value; sticky session affinity
   is also required if you run multiple replicas without a shared
   secret store."
  (:require [clojure.tools.logging :as log])
  (:import  [java.security MessageDigest SecureRandom]
            [java.util Base64]
            [javax.crypto Mac]
            [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Secret

(defn- random-bytes [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    b))

(defonce ^:private secret
  (delay
    (if-let [env (System/getenv "WUN_CSRF_SECRET")]
      (.getBytes ^String env "UTF-8")
      (do (log/warnf "wun.csrf: WUN_CSRF_SECRET not set; generating ephemeral secret. Tokens will not survive a server restart and won't work across replicas.")
          (random-bytes 32)))))

(defn reset-secret-for-test!
  "Reset the lazy secret so tests can set WUN_CSRF_SECRET fresh
   between cases. Production code must never call this."
  []
  (alter-var-root #'secret (fn [_] (delay
                                     (if-let [env (System/getenv "WUN_CSRF_SECRET")]
                                       (.getBytes ^String env "UTF-8")
                                       (random-bytes 32))))))

;; ---------------------------------------------------------------------------
;; HMAC

(defn- hmac-sha256 [^bytes key ^bytes msg]
  (let [m (Mac/getInstance "HmacSHA256")]
    (.init m (SecretKeySpec. key "HmacSHA256"))
    (.doFinal m msg)))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- bytes-eq?
  "Constant-time compare so token validation can't leak by timing."
  [^bytes a ^bytes b]
  (and a b (MessageDigest/isEqual a b)))

;; ---------------------------------------------------------------------------
;; Token API

(defn issue
  "Issue a CSRF token bound to `binding-key` (a session token, or the
   conn-id for anonymous sessions). Pure: identical input + secret
   produces identical output, so the same client reconnecting can
   derive the same token from the same binding without storing it
   server-side."
  [binding-key]
  (b64url (hmac-sha256 @secret (.getBytes ^String binding-key "UTF-8"))))

(defn valid?
  "Validate `token` against the expected token derived from `binding-key`.
   Constant-time."
  [binding-key token]
  (and (string? token)
       (let [expected (.getBytes ^String (issue binding-key) "UTF-8")
             actual   (.getBytes ^String token              "UTF-8")]
         (bytes-eq? expected actual))))

;; ---------------------------------------------------------------------------
;; Enforcement toggle. Defaults to true. Operators with an installed base
;; of pre-CSRF native clients can flip `WUN_CSRF_REQUIRED=false` until
;; their app builds catch up; the server still issues tokens (no behaviour
;; change for clients that DO echo them) but won't reject unsigned posts.

(defn required?
  "True iff CSRF token validation should reject requests that fail.
   Reads `WUN_CSRF_REQUIRED` env var (default true)."
  []
  (let [v (System/getenv "WUN_CSRF_REQUIRED")]
    (if (nil? v) true (not= (.toLowerCase ^String v) "false"))))

;; ---------------------------------------------------------------------------
;; Pedestal interceptor wiring is in wun.server.http; this namespace
;; stays pure so it can be unit-tested without Pedestal on the
;; classpath.
