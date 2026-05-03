(ns wun.server.broadcast-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.broadcast :as bc]
            [wun.server.pubsub    :as pubsub]
            [wun.server.state     :as state]))

(use-fixtures :each
  (fn [t]
    (let [conns @state/connections
          conn-states @state/conn-states]
      (try
        (reset! state/connections {})
        (reset! state/conn-states {})
        (pubsub/reset-state!)
        (bc/register-rebroadcast-fn! (constantly nil))
        (t)
        (finally
          (reset! state/connections conns)
          (reset! state/conn-states conn-states)
          (pubsub/reset-state!)
          (bc/register-rebroadcast-fn! (constantly nil)))))))

(deftest subscribed-conns-morph-and-rebroadcast
  (let [rebroadcasts (atom [])]
    (bc/register-rebroadcast-fn! (fn [cid] (swap! rebroadcasts conj cid)))
    (bc/subscribe! :feed/main "cid-A"
                   (fn [s msg] (update s :feed (fnil conj []) msg)))
    (bc/subscribe! :feed/main "cid-B"
                   (fn [s msg] (update s :feed (fnil conj []) msg)))
    (bc/publish! :feed/main {:hi true})
    (is (= [{:hi true}] (:feed (state/state-for "cid-A"))))
    (is (= [{:hi true}] (:feed (state/state-for "cid-B"))))
    ;; Both conns trigger a re-broadcast.
    (is (= ["cid-A" "cid-B"]
           (sort @rebroadcasts)))))

(deftest unsubscribe-stops-applying-morphs
  (let [tok (bc/subscribe! :feed/main "cid-A"
                           (fn [s msg] (update s :feed (fnil conj []) msg)))]
    (bc/publish! :feed/main {:n 1})
    (bc/unsubscribe! tok)
    (bc/publish! :feed/main {:n 2})
    (is (= [{:n 1}] (:feed (state/state-for "cid-A"))))))

(deftest different-conns-can-use-different-morphs
  (bc/subscribe! :news/main "cid-A"
                 (fn [s msg] (update s :unread (fnil inc 0))))
  (bc/subscribe! :news/main "cid-B"
                 (fn [s msg] (assoc s :latest msg)))
  (bc/publish! :news/main {:title "headline"})
  (is (= 1 (:unread (state/state-for "cid-A"))))
  (is (= {:title "headline"}
         (:latest (state/state-for "cid-B")))))
