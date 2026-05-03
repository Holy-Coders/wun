(ns wun.server.dashboard-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.dashboard      :as dashboard]
            [wun.server.dashboard.tap  :as tap]
            [wun.server.state          :as state]
            [wun.server.telemetry      :as telemetry]
            [wun.screens               :as screens]))

;; `ticker-handle` is private; re-deref through the var to read the
;; value it holds (deref var → atom; deref atom → value).
(defn- ticker-value [] @@#'dashboard/ticker-handle)

(use-fixtures :each
  (fn [test-fn]
    (telemetry/clear-sinks!)
    (dashboard/uninstall!)
    (reset! state/connections {})
    (reset! state/conn-states {})
    (try (test-fn)
         (finally
           (dashboard/uninstall!)
           (reset! state/connections {})
           (reset! state/conn-states {})
           (telemetry/clear-sinks!)))))

(defn- fake-conn! [conn-id screen-key]
  ;; A connection is a map keyed by an opaque event-channel object.
  ;; The dashboard only cares about :screen-stack + :conn-id, so an
  ;; arbitrary key suffices.
  (let [event-ch (Object.)]
    (swap! state/connections assoc event-ch
           {:conn-id      conn-id
            :screen-stack [screen-key]
            :caps         {}
            :fmt          :transit})
    event-ch))

(deftest refresh-once-injects-snapshot-into-dashboard-conns-only
  (tap/install!)
  (fake-conn! "dash-a"  :wun.dashboard/main)
  (fake-conn! "dash-b"  :wun.dashboard/main)
  (fake-conn! "user-c"  :counter/main)
  ;; Stub out broadcast so the test doesn't need wun.server.http loaded.
  (reset! @#'dashboard/broadcast-fn (fn [_cid] nil))
  (let [refreshed (dashboard/refresh-once!)]
    (is (= 2 refreshed))
    (is (some? (:wun.dashboard/snapshot (state/state-for "dash-a"))))
    (is (some? (:wun.dashboard/snapshot (state/state-for "dash-b"))))
    (is (nil?  (:wun.dashboard/snapshot (state/state-for "user-c"))))))

(deftest install-refused-under-prod-without-force
  (with-redefs [dashboard/prod?   (constantly true)
                dashboard/forced? (constantly false)]
    (is (= :refused-prod (dashboard/install!)))
    (is (nil? (ticker-value)))))

(deftest install-allowed-under-prod-with-force-flag
  (with-redefs [dashboard/prod?   (constantly true)
                dashboard/forced? (constantly true)]
    (is (= :ok (dashboard/install!)))
    (is (some? (ticker-value)))))

(deftest install-is-idempotent
  (is (= :ok (dashboard/install!)))
  (let [h1 (ticker-value)]
    (is (= :ok (dashboard/install!)))
    (is (identical? h1 (ticker-value)))))

(deftest dashboard-screen-is-registered-after-loading-dashboard-ns
  ;; `wun.server.dashboard` requires `wun.dashboard.screen`, which
  ;; side-effects the screen registration on ns load. As long as the
  ;; dashboard ns has been loaded once this JVM, the screen is in
  ;; the registry — no `install!` call needed for that.
  (is (some? (screens/lookup :wun.dashboard/main))))
