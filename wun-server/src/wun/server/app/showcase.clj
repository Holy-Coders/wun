(ns wun.server.app.showcase
  "Server-side glue for the built-in showcase live demo. Mirrors
   `templates/fragments/showcase/src/myapp/server/showcase.clj` but
   keyed on `:wun.app.showcase/*` instead of `:myapp.showcase/*` so
   the framework's own dev loop (`wun dev` from the repo root) can
   load and exercise it without a consumer app present.

   Two server-only behaviours back the live demo:
     - When a connection enters `:wun.app.showcase/live`, register
       it with presence so the roster updates everywhere.
     - When the bump intent fires on any conn, fan the new counter
       out to every other live-screen conn so all viewers see it."
  (:require [clojure.tools.logging :as log]
            [wun.server.state      :as state]
            [wun.server.presence   :as presence]
            [wun.server.telemetry  :as telemetry]))

(def ^:private live-topic       "wun.app.showcase.live")
(def ^:private live-screen-key  :wun.app.showcase/live)

(defn- on-live-screen? [conn-meta]
  (= live-screen-key (peek (:screen-stack conn-meta))))

(defn- live-conn-ids []
  (->> @state/connections
       vals
       (filter on-live-screen?)
       (keep :conn-id)
       distinct))

(defn- inject-presence! []
  (let [present (vec (live-conn-ids))]
    (doseq [cid present]
      (state/swap-state-for! cid assoc-in
                             [:wun.app.showcase/live :present] present))))

(defn- inject-counter! [n]
  (doseq [cid (live-conn-ids)]
    (state/swap-state-for! cid assoc-in
                           [:wun.app.showcase/live :count] n)))

(defn- broadcast-live! []
  (when-let [bcast (try (requiring-resolve 'wun.server.http/broadcast-to-conn!)
                        (catch Throwable _ nil))]
    (doseq [cid (live-conn-ids)]
      (bcast cid))))

(defn- showcase-sink [event-key {:keys [conn-id intent screen-key]}]
  (case event-key
    :wun/connect
    (when (= live-screen-key screen-key)
      (presence/join! live-topic conn-id {})
      (inject-presence!)
      (broadcast-live!))

    :wun/disconnect
    (when (presence/topics-of conn-id)
      (presence/leave! live-topic conn-id)
      (inject-presence!)
      (broadcast-live!))

    :wun/intent.applied
    (when (= :wun.app.showcase/live-bump intent)
      (let [n (or (some-> (first (live-conn-ids))
                          state/state-for
                          (get-in [:wun.app.showcase/live :count]))
                  0)]
        (inject-counter! n)
        (broadcast-live!)))

    nil))

(defonce ^:private installed? (atom false))

(defn init!
  "Register the showcase telemetry sink. Idempotent."
  []
  (when (compare-and-set! installed? false true)
    (telemetry/register-sink! showcase-sink)
    (log/info "wun.server.app.showcase: live demo wiring installed"))
  :ok)
