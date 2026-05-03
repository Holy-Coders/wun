(ns wun.dashboard.screen
  "The Wun live dev dashboard, expressed as an ordinary Wun screen.

   This is the dogfood: the dashboard renders through the same
   defscreen / patch / SSE pipeline as user screens. There is no
   privileged path. The render fn is pure and reads from per-conn
   state under `:wun.dashboard/snapshot`; the server-side ticker
   in `wun.server.dashboard` is responsible for swapping a fresh
   snapshot into that key on every refresh tick.

   Renders only built-in `:wun/*` components so a fresh app gets the
   dashboard for free without registering anything extra."
  (:require [wun.screens :refer [defscreen]]))

(def ^:private path "/_wun/dashboard")

(defn- fmt-uptime [ms]
  (let [s (quot ms 1000)]
    (cond
      (< s 60)    (str s "s")
      (< s 3600)  (str (quot s 60) "m " (mod s 60) "s")
      :else       (str (quot s 3600) "h " (mod (quot s 60) 60) "m"))))

(defn- fmt-mean [n]
  (when n (str (/ (Math/round (* (double n) 100.0)) 100.0) " ms")))

(defn- stat-card [label value]
  [:wun/Card {}
   [:wun/Stack {:gap 4 :padding 12}
    [:wun/Text {:variant :body} label]
    [:wun/Heading {:level 2} (str value)]]])

(defn- header [{:keys [active-conns uptime-ms registries]}]
  [:wun/Stack {:gap 12}
   [:wun/Heading {:level 1} "Wun dashboard"]
   [:wun/Text {:variant :body}
    "Live snapshot of the running server. Refreshed once per second."]
   [:wun/Stack {:direction :row :gap 12}
    (stat-card "Active connections" active-conns)
    (stat-card "Uptime"              (fmt-uptime uptime-ms))
    (stat-card "Components"          (:components registries))
    (stat-card "Screens"             (:screens    registries))
    (stat-card "Intents"             (:intents    registries))]])

(defn- conn-row [{:keys [conn-id screen fmt]}]
  [:wun/Stack {:direction :row :gap 16 :padding 8}
   [:wun/Text {:variant :body} (str conn-id)]
   [:wun/Text {:variant :body} (str (or screen "—"))]
   [:wun/Badge {:tone :info}   (name (or fmt :transit))]])

(defn- connections-section [{:keys [connections]}]
  [:wun/Card {:title "Connections"}
   (if (empty? connections)
     [:wun/Text {:variant :body} "No active SSE connections."]
     (into [:wun/Stack {:gap 4}]
           (map conn-row connections)))])

(defn- intent-row [{:keys [intent count errors mean-ms]}]
  [:wun/Stack {:direction :row :gap 16 :padding 8}
   [:wun/Text  {:variant :body} (str intent)]
   [:wun/Text  {:variant :body} (str count " calls")]
   [:wun/Text  {:variant :body} (or (fmt-mean mean-ms) "—")]
   [:wun/Badge {:tone (if (pos? errors) :danger :success)}
    (str errors " err")]])

(defn- intents-section [{:keys [intent-metrics]}]
  [:wun/Card {:title "Intents"}
   (if (empty? intent-metrics)
     [:wun/Text {:variant :body}
      "No intents have been dispatched yet."]
     (into [:wun/Stack {:gap 4}]
           (map intent-row intent-metrics)))])

(defn- event-tone [k]
  (let [s (str k)]
    (cond
      (or (.endsWith s ".rejected")
          (.endsWith s ".miss")
          (.endsWith s ".error")
          (.endsWith s ".block")
          (.endsWith s ".drop"))    :danger
      (.endsWith s ".applied")      :success
      (.endsWith s ".dedup")        :warning
      :else                         :info)))

(defn- event-row [{:keys [t key attrs]}]
  [:wun/Stack {:direction :row :gap 12 :padding 6}
   [:wun/Badge {:tone (event-tone key)} (str key)]
   [:wun/Text  {:variant :body} (pr-str attrs)]
   [:wun/Text  {:variant :body} (str t)]])

(defn- events-section [{:keys [recent-events]}]
  [:wun/Card {:title "Recent telemetry"}
   (if (empty? recent-events)
     [:wun/Text {:variant :body} "No events captured yet."]
     [:wun/ScrollView {:direction :vertical}
      (into [:wun/Stack {:gap 2}]
            (map event-row (take 50 recent-events)))])])

(defn- empty-state []
  [:wun/Stack {:gap 12 :padding 24}
   [:wun/Heading {:level 1} "Wun dashboard"]
   [:wun/Text {:variant :body}
    "Waiting for the first refresh tick. If this persists, the dashboard
     server-side install! may not have run."]])

(defn render [state]
  (if-let [snap (:wun.dashboard/snapshot state)]
    [:wun/Stack {:gap 16 :padding 24}
     (header snap)
     [:wun/Divider {:thickness 1}]
     (connections-section snap)
     (intents-section snap)
     (events-section snap)]
    (empty-state)))

(defscreen :wun.dashboard/main
  {:path    path
   :present :push
   :meta    (fn [_state] {:title "Wun dashboard"})
   :render  render})
