(ns wun.web.intent-bus
  "Optimistic intent dispatcher and reconciler. The brief's marquee
   thesis: one morph fn defined in shared .cljc, run on both server
   (authoritative) and client (optimistic prediction).

   State on the client:

     confirmed-state  -- screen state mirrored from the server's
                         envelopes. Updated on every server frame.
     confirmed-tree   -- tree the server's per-connection prior tracks.
                         Updated by applying envelope patches.
     pending          -- ordered vec of {:id :intent :params :created-at}
                         the user has fired but the server hasn't
                         confirmed yet. Tagged with a wall-clock
                         timestamp so stale entries can be GC'd.
     display-tree     -- what the DOM is rendered against. Always
                         equals (render-screen pred-state) where
                         pred-state = (reduce morph confirmed-state
                                       pending).

   Dispatch path:
     1. Append {:id :intent :params :created-at} to pending.
     2. Recompute display-tree (predicted state -> render).
     3. POST the intent envelope; the server will broadcast a patch
        tagged with :resolves-intent equal to this id.

   Reconcile path (per server envelope):
     1. If the envelope contains a :replace at root path, treat it
        as a fresh server view (initial connect or reconnect after
        disconnect) and clear pending. The new tree already reflects
        whatever the server processed; pending entries are stale.
     2. Apply patches to confirmed-tree.
     3. Replace confirmed-state with the envelope's :state when
        present.
     4. Drop the entry whose :id matches :resolves-intent from pending.
     5. Recompute display-tree. If our prediction matched, no visible
        change. If it didn't, the tree converges to the new
        authoritative prediction (the brief's match / refine /
        conflict semantics).

   Pending TTL: a periodic GC drops pending entries older than
   pending-ttl-ms. POST failures that the client didn't catch (rare
   with fetch's promise rejection, but possible) would otherwise
   leave a permanent ghost in pending; the TTL bounds the damage."
  (:require [cognitect.transit :as transit]
            [reagent.core      :as r]
            [wun.diff          :as diff]
            [wun.intents       :as intents]
            [wun.screens       :as screens]))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; Phase 1.C uses one screen. Phase 1.D-routing adds path-based routing.
(def ^:private screen-key :counter/main)

;; Pending entries older than this get evicted on the GC tick.
(def ^:private pending-ttl-ms 30000)

;; How often the GC tick runs.
(def ^:private gc-interval-ms 5000)

;; ---------------------------------------------------------------------------
;; State

(defonce confirmed-state (atom nil))
(defonce confirmed-tree  (atom nil))
(defonce pending         (atom []))
;; display-tree is a reagent atom so the top-level component can deref
;; it reactively; reagent re-renders only when this changes.
(defonce display-tree    (r/atom nil))

(defn- now-ms [] (.getTime (js/Date.)))

(defn- predicted-state []
  (reduce (fn [s {:keys [intent params]}]
            (intents/apply-intent s intent params))
          @confirmed-state
          @pending))

(defn recompute! []
  (reset! display-tree (screens/render screen-key (predicted-state))))

;; ---------------------------------------------------------------------------
;; Transit + POST

(def ^:private writer (transit/writer :json))

(defn- t->str [v] (transit/write writer v))

(defn- post-intent! [intent params id]
  (-> (js/fetch (str server-base "/intent")
                #js {:method  "POST"
                     :headers #js {"Content-Type" "application/transit+json"}
                     :body    (t->str {:intent intent
                                       :params (or params {})
                                       :id     id})})
      (.catch (fn [e] (js/console.error "wun: intent failed" e)))))

;; ---------------------------------------------------------------------------
;; Public dispatch + reconcile

(defn dispatch!
  "Optimistically fire `intent` with `params`. Validates params against
   the intent's :params schema first; on failure, logs a warning and
   drops the intent without optimistic prediction or POST. On success,
   appends a pending entry, recomputes the predicted display, and
   POSTs the envelope to the server. Returns the intent id, or nil
   when validation rejected the call."
  [intent params]
  (let [params (or params {})]
    (if-let [err (intents/validate-params intent params)]
      (do (js/console.warn "wun: invalid intent params" (clj->js err))
          nil)
      (let [id (random-uuid)]
        (swap! pending conj {:id         id
                             :intent     intent
                             :params     params
                             :created-at (now-ms)})
        (recompute!)
        (post-intent! intent params id)
        id))))

(defn- bootstrap-frame?
  "True when an envelope's patches contain a :replace at root, which
   the server emits on (re)connect or screen-level restructure. We
   treat this as a fresh server view and drop pending."
  [patches]
  (some (fn [{:keys [op path]}]
          (and (= op :replace) (= [] path)))
        patches))

(defn apply-envelope!
  "Reconcile a server envelope: maybe-bootstrap, apply patches against
   confirmed-tree, mirror confirmed-state, drop the resolved pending
   entry, and recompute the display."
  [{:keys [patches state resolves-intent status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (when (and (seq patches) (bootstrap-frame? patches))
    (when (seq @pending)
      (js/console.info "wun: bootstrap frame; dropping" (count @pending) "pending intent(s)"))
    (reset! pending []))
  (when (seq patches)
    (swap! confirmed-tree diff/apply-patches patches))
  (when (some? state)
    (reset! confirmed-state state))
  (when resolves-intent
    (swap! pending (fn [ps] (vec (remove #(= (:id %) resolves-intent) ps)))))
  (recompute!))

;; ---------------------------------------------------------------------------
;; Pending GC

(defn- gc-pending! []
  (let [cutoff (- (now-ms) pending-ttl-ms)
        before (count @pending)
        live   (vec (remove #(< (:created-at % 0) cutoff) @pending))]
    (when (< (count live) before)
      (js/console.warn "wun: dropping" (- before (count live))
                       "stale pending intent(s) older than"
                       pending-ttl-ms "ms")
      (reset! pending live)
      (recompute!))))

(defonce ^:private gc-handle (atom nil))

(defn start-pending-gc! []
  (when-let [h @gc-handle] (js/clearInterval h))
  (reset! gc-handle (js/setInterval gc-pending! gc-interval-ms)))
