(ns wun.web.intent-bus
  "Optimistic intent dispatcher and reconciler. The brief's marquee
   thesis: one morph fn defined in shared .cljc, run on both server
   (authoritative) and client (optimistic prediction).

   State on the client:

     confirmed-state  -- screen state mirrored from the server's
                         envelopes. Updated on every server frame.
     confirmed-tree   -- tree the server's per-connection prior tracks.
                         Updated by applying envelope patches.
     pending          -- ordered vec of {:id :intent :params} the user
                         has fired but the server hasn't confirmed.
     display-tree     -- what the DOM is rendered against. Always
                         equals (render-screen pred-state) where
                         pred-state = (reduce morph confirmed-state
                                       pending).

   Dispatch path:
     1. Append {:id :intent :params} to pending.
     2. Recompute display-tree (predicted state -> render).
     3. POST the intent envelope; the server will broadcast a patch
        tagged with :resolves-intent equal to this id.

   Reconcile path (per server envelope):
     1. Apply patches to confirmed-tree.
     2. Replace confirmed-state with the envelope's :state when
        present.
     3. Drop the entry whose :id matches :resolves-intent from pending.
     4. Recompute display-tree. If our prediction matched, no visible
        change. If it didn't, the tree converges to the new authoritative
        prediction (the brief's match / refine / conflict semantics).

   Phase 1.D will gate prediction by the intent's `:optimistic?` flag
   and move from a hardcoded screen to path-based routing."
  (:require [cognitect.transit :as transit]
            [wun.diff          :as diff]
            [wun.intents       :as intents]
            [wun.screens       :as screens]))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; Phase 1.C uses one screen. Phase 1.D adds path-based routing.
(def ^:private screen-key :counter/main)

;; ---------------------------------------------------------------------------
;; State

(defonce confirmed-state (atom nil))
(defonce confirmed-tree  (atom nil))
(defonce pending         (atom []))
(defonce display-tree    (atom nil))

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
        (swap! pending conj {:id id :intent intent :params params})
        (recompute!)
        (post-intent! intent params id)
        id))))

(defn apply-envelope!
  "Reconcile a server envelope: apply patches against confirmed-tree,
   mirror confirmed-state, drop the resolved pending entry, and
   recompute the display."
  [{:keys [patches state resolves-intent status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (when (seq patches)
    (swap! confirmed-tree diff/apply-patches patches))
  (when (some? state)
    (reset! confirmed-state state))
  (when resolves-intent
    (swap! pending (fn [ps] (vec (remove #(= (:id %) resolves-intent) ps)))))
  (recompute!))
