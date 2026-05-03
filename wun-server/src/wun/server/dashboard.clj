(ns wun.server.dashboard
  "Live dev-server dashboard. Opt-in: an app calls `(install!)` from
   its server init (or wires it via env var); the framework does not
   mount it for you because exposing per-connection state by default
   in a production process is a foot-gun.

   `install!` performs three actions:
   1. Loads `wun.dashboard.screen` so `defscreen :wun.dashboard/main`
      registers and `/_wun/dashboard` becomes routable through the
      normal SSE bootstrap.
   2. Installs `wun.server.dashboard.tap` as a telemetry sink so the
      ring buffer + per-intent metrics start filling.
   3. Starts a 1 Hz `ScheduledExecutor` ticker that walks
      `@state/connections`, finds connections currently rendering
      `:wun.dashboard/main`, swaps the latest snapshot into their
      per-conn state under `:wun.dashboard/snapshot`, and triggers a
      broadcast through the standard `broadcast-to-conn!` path.

   The polling tick is the simplest implementation; an event-driven
   re-broadcast (re-render on every relevant telemetry event) would
   use less CPU when idle but is harder to throttle when busy. Swap
   later if a profile shows it matters.

   Production posture: refuses to mount when `WUN_PROFILE=prod`
   unless `WUN_DASHBOARD_FORCE=1` is also set. Phoenix LiveDashboard
   takes the same opt-in posture and it's the right default — a
   live state inspector is dev-loop infrastructure, not something
   you want listening on a production host without a deliberate
   choice."
  (:require [clojure.tools.logging      :as log]
            [wun.server.dashboard.tap   :as tap]
            [wun.server.state           :as state]
            [wun.dashboard.screen]) ;; side-effecting :defscreen registration
  (:import  [java.util.concurrent Executors ScheduledExecutorService
                                  ThreadFactory TimeUnit]))

(def ^:private dashboard-screen-key :wun.dashboard/main)

(defonce ^:private broadcast-fn
  ;; Resolved lazily so this ns has no compile-time dep on wun.server.http
  ;; (which pulls Pedestal, wire, pubsub — a heavy graph for code that just
  ;; needs to nudge a single connection). Tests stub via `reset!`.
  (atom nil))

(defn- resolve-broadcast! []
  (or @broadcast-fn
      (when-let [v (try (requiring-resolve 'wun.server.http/broadcast-to-conn!)
                        (catch Throwable _ nil))]
        (reset! broadcast-fn @v)
        @broadcast-fn)))

(defn- on-dashboard? [conn-meta]
  (= dashboard-screen-key (peek (:screen-stack conn-meta))))

(defn- dashboard-conn-ids []
  (->> @state/connections
       vals
       (filter on-dashboard?)
       (keep :conn-id)
       distinct))

(defn- inject-snapshot! [conn-id snap]
  ;; state/swap-state-for! preserves any other state slices the
  ;; dashboard's own user code may have stashed under the conn.
  (state/swap-state-for! conn-id assoc :wun.dashboard/snapshot snap))

(defn refresh-once!
  "One pass of the refresh loop, exposed for testing. Snapshots the
   tap, walks dashboard connections, and rebroadcasts each. Returns
   the number of connections refreshed."
  []
  (let [snap     (tap/snapshot)
        broadcast (resolve-broadcast!)
        conn-ids (dashboard-conn-ids)]
    (doseq [cid conn-ids]
      (inject-snapshot! cid snap)
      (when broadcast (broadcast cid)))
    (count conn-ids)))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defonce ^:private ^ScheduledExecutorService ticker-pool
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ r]
       (doto (Thread. r "wun-dashboard-ticker")
         (.setDaemon true))))))

(defonce ^:private ticker-handle (atom nil))

(def ^:private tick-interval-ms 1000)

(defn- prod? []
  (= "prod" (System/getenv "WUN_PROFILE")))

(defn- forced? []
  (= "1" (System/getenv "WUN_DASHBOARD_FORCE")))

(defn- start-ticker! []
  (when-not @ticker-handle
    (let [handle (.scheduleAtFixedRate
                   ticker-pool
                   ^Runnable (fn []
                               (try (refresh-once!)
                                    (catch Throwable t
                                      (log/warn t "wun.dashboard: refresh tick threw"))))
                   tick-interval-ms tick-interval-ms TimeUnit/MILLISECONDS)]
      (reset! ticker-handle handle))))

(defn install!
  "Mount the dashboard. Idempotent: a second call is a no-op. Returns
   `:ok` on success, `:refused-prod` when `WUN_PROFILE=prod` and not
   force-overridden."
  []
  (cond
    @ticker-handle :ok

    (and (prod?) (not (forced?)))
    (do (log/warn "wun.dashboard: refusing to install under WUN_PROFILE=prod (set WUN_DASHBOARD_FORCE=1 to override)")
        :refused-prod)

    :else
    (do (tap/install!)
        (start-ticker!)
        (log/info "wun.dashboard: mounted at /_wun/dashboard")
        :ok)))

(defn uninstall!
  "Stop the refresh ticker and unregister the telemetry tap. Idempotent.
   Tests use this; production code rarely needs it."
  []
  (when-let [h @ticker-handle]
    (.cancel h false)
    (reset! ticker-handle nil))
  (tap/uninstall!)
  :ok)
