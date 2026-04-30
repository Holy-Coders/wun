(ns wun.web.persist
  "Hot-cache persistence for SDUI. On cold start (page reload, app
   restart) we hydrate the last-known confirmed-tree + state +
   screen-stack + meta from localStorage so the user sees the prior
   UI immediately rather than a blank page; the SSE stream
   reconciles within a few hundred ms.

   Mirrors LiveView's idiom of keeping the previously rendered DOM
   on screen during a reconnect, except this also survives a
   full reload because we round-trip through localStorage.

   Storage shape (one entry per pathname so navigation between
   screens preserves each independently):

     localStorage[\"wun:state:/\"] -> transit string of
       {:confirmed-state ...
        :confirmed-tree  ...
        :screen-stack    [:counter/main]
        :meta            {:title ...}
        :saved-at        epoch-ms}

   The persisted snapshot is treated as advisory -- the client renders
   it on hydrate but does NOT trust it as authoritative; the next
   server envelope replaces it.

   Bounded by localStorage's ~5MB budget; trees are typically <100KB.
   We discard snapshots older than `stale-ms` (default: 24h)."
  (:require [cognitect.transit :as transit]))

(def ^:private stale-ms (* 24 60 60 1000))

(def ^:private writer (transit/writer :json))
(def ^:private reader (transit/reader :json))

(defn- key-for [path] (str "wun:state:" path))

(defn- pathname []
  (or (some-> js/window .-location .-pathname) "/"))

(defn save!
  "Persist a snapshot keyed by the current path. Quietly swallows
   QuotaExceededError -- localStorage being full is not a fatal bug
   in a hot-cache."
  [{:keys [confirmed-state confirmed-tree screen-stack meta]}]
  (try
    (let [snap {:confirmed-state confirmed-state
                :confirmed-tree  confirmed-tree
                :screen-stack    screen-stack
                :meta            meta
                :saved-at        (.getTime (js/Date.))}]
      (.setItem (.-localStorage js/window)
                (key-for (pathname))
                (transit/write writer snap)))
    (catch :default e
      (js/console.warn "wun: persist save failed" e))))

(defn load
  "Returns the cached snapshot (or nil) for the current path. Drops
   stale snapshots so old apps don't permanently shadow an empty
   server."
  []
  (try
    (when-let [raw (.getItem (.-localStorage js/window) (key-for (pathname)))]
      (let [snap (transit/read reader raw)
            age  (- (.getTime (js/Date.)) (:saved-at snap 0))]
        (when (< age stale-ms) snap)))
    (catch :default e
      (js/console.warn "wun: persist load failed" e)
      nil)))

(defn clear! []
  (try (.removeItem (.-localStorage js/window) (key-for (pathname)))
       (catch :default _ nil)))
