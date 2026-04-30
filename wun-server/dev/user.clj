(ns user
  (:require [wun.server.core :as core]
            [wun.server.intents :as intents]
            [wun.server.state :as state]))

(defn restart! []
  (core/stop!)
  (core/start!))

(comment
  (core/start!)
  (core/stop!)
  @state/app-state
  @intents/registry
  ,)
