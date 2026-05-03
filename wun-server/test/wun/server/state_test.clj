(ns wun.server.state-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.state :as state]))

(use-fixtures :each
  (fn [t]
    ;; Snapshot + restore the global atoms so tests stay independent.
    (let [conns @state/connections
          conn-states @state/conn-states
          init-fn @state/init-state-fn]
      (try (reset! state/connections {})
           (reset! state/conn-states {})
           (t)
           (finally
             (reset! state/connections conns)
             (reset! state/conn-states conn-states)
             (reset! state/init-state-fn init-fn))))))

(deftest state-for-defaults-to-init
  (with-redefs [state/*init-state* {:counter 0}]
    (is (= {:counter 0} (state/state-for "no-such-cid")))))

(deftest swap-state-for-creates-slice
  (state/swap-state-for! "cid-1" assoc :counter 5)
  (is (= 5 (:counter (state/state-for "cid-1")))))

(deftest swap-state-for-runs-init-fn
  (state/register-init-state-fn! (fn [cid] {:cid cid :booted? true}))
  (state/ensure-state-for! "cid-2")
  (is (= {:cid "cid-2" :booted? true} (state/state-for "cid-2"))))

(deftest add-and-remove-connection-evicts-slice
  (let [ch (a/chan 1)]
    (state/add-connection! ch {:wun/Stack 1} :transit :counter/main "cid-A")
    (is (= "cid-A" (state/conn-id ch)))
    (is (contains? @state/conn-states "cid-A"))
    (state/remove-connection! ch)
    (is (not (contains? @state/conn-states "cid-A")))))

(deftest multiple-channels-share-slice
  (let [ch1 (a/chan 1) ch2 (a/chan 1)]
    (state/add-connection! ch1 {} :transit :s/m "cid-X")
    (state/add-connection! ch2 {} :transit :s/m "cid-X")
    (state/swap-state-for! "cid-X" assoc :v 7)
    (state/remove-connection! ch1)
    ;; Slice survives because cid-X still has ch2.
    (is (= 7 (:v (state/state-for "cid-X"))))
    (state/remove-connection! ch2)
    (is (not (contains? @state/conn-states "cid-X")))))

(deftest screen-stack-push-pop-replace
  (let [ch (a/chan 1)]
    (state/add-connection! ch {} :transit :counter/main "cid-S")
    (is (= [:counter/main] (state/screen-stack ch)))
    (state/push-screen! ch :about/main)
    (is (= [:counter/main :about/main] (state/screen-stack ch)))
    (state/replace-screen! ch :counter/main)
    (is (= [:counter/main :counter/main] (state/screen-stack ch)))
    (state/pop-screen! ch)
    (is (= [:counter/main] (state/screen-stack ch)))
    ;; pop never reduces below 1
    (state/pop-screen! ch)
    (is (= [:counter/main] (state/screen-stack ch)))))

(deftest webframe-stash-and-evict
  (state/stash-webframe! "tok-1" [:wun/Text {} "hi"])
  (is (= [:wun/Text {} "hi"] (state/webframe-tree "tok-1")))
  ;; Beyond the cap the FIFO evicts the oldest.
  (dotimes [i 1100]
    (state/stash-webframe! (str "t" i) [:wun/Text {} (str i)]))
  (is (nil? (state/webframe-tree "tok-1"))))

(deftest intent-cache-is-bounded-and-lru-by-insertion
  (state/cache-intent! "id-1" {:status :ok :resolves-intent "id-1"})
  (is (= {:status :ok :resolves-intent "id-1"} (state/cached-intent "id-1")))
  ;; Re-cache same id is a no-op, doesn't bump the queue.
  (state/cache-intent! "id-1" {:status :other})
  (is (= {:status :ok :resolves-intent "id-1"} (state/cached-intent "id-1")))
  ;; Overflow evicts oldest first.
  (dotimes [i 1100] (state/cache-intent! (str "x" i) {:n i}))
  (is (nil? (state/cached-intent "id-1"))))

(deftest closed?-detects-closed-channel
  (let [ch (a/chan 1)]
    (is (not (state/closed? ch)))
    (a/close! ch)
    (is (state/closed? ch))))

(deftest evict-closed!-drops-only-closed-channels
  (let [ch1 (a/chan 1) ch2 (a/chan 1)]
    (state/add-connection! ch1 {} :transit :s/m "cid-1")
    (state/add-connection! ch2 {} :transit :s/m "cid-2")
    (a/close! ch1)
    (is (= 1 (state/evict-closed!)))
    (is (= 1 (state/connection-count)))
    (is (= "cid-2" (state/conn-id ch2)))
    ;; Slice for cid-1 also gone.
    (is (not (contains? @state/conn-states "cid-1")))))

(deftest channel-by-conn-id-roundtrip
  (let [ch (a/chan 1)]
    (state/add-connection! ch {} :transit :s/m "cid-RT")
    (is (= ch (state/channel-by-conn-id "cid-RT")))
    (is (nil? (state/channel-by-conn-id "no-such-cid")))))
