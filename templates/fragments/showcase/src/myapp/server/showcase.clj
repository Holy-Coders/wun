(ns myapp.server.showcase
  "Server-side glue for the showcase live demo. The demo screen
   `:myapp.showcase/live` renders a shared counter + a presence
   list. Two pieces are server-only:

     - When a connection enters the live screen, register it with
       presence so the roster updates everywhere.
     - When the bump intent fires on any conn, fan the new counter
       out to every other live-screen conn so all viewers see it.

   The broadcast piggy-backs on the framework's existing
   per-conn render/SSE pipeline; we just nudge the right
   `broadcast-to-conn!` calls. No custom transport.

   `init!` is idempotent — running it more than once just leaves
   the same telemetry sink registered. Apps wire it into
   `server/main.clj` next to the dashboard install."
  (:require [clojure.tools.logging :as log]
            [wun.server.state      :as state]
            [wun.server.presence   :as presence]
            [wun.server.telemetry  :as telemetry]))

(def ^:private live-topic "showcase.live")
(def ^:private live-screen-key :myapp.showcase/live)

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
                             [:wun.showcase/live :present] present))))

(defn- inject-counter! [n]
  (doseq [cid (live-conn-ids)]
    (state/swap-state-for! cid assoc-in
                           [:wun.showcase/live :count] n)))

(defn- broadcast-live! []
  (when-let [bcast (try (requiring-resolve 'wun.server.http/broadcast-to-conn!)
                        (catch Throwable _ nil))]
    (doseq [cid (live-conn-ids)]
      (bcast cid))))

(defn- showcase-sink [event-key {:keys [conn-id intent screen-key]}]
  (case event-key
    ;; Presence: track entry / exit on the live screen.
    :wun/connect
    (when (= live-screen-key screen-key)
      (presence/join! live-topic conn-id {})
      (inject-presence!)
      (broadcast-live!))

    :wun/disconnect
    (when (presence/topics-of conn-id) ;; cheap "did we ever join?" check
      (presence/leave! live-topic conn-id)
      (inject-presence!)
      (broadcast-live!))

    ;; Counter: when any live conn bumps, fan the new value out.
    :wun/intent.applied
    (when (= :wun.showcase/live-bump intent)
      (let [n (or (some-> (first (live-conn-ids))
                          state/state-for
                          (get-in [:wun.showcase/live :count]))
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
    (log/info "myapp.server.showcase: live demo wiring installed"))
  :ok)
