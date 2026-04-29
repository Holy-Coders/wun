(ns wun.web.intent-bus
  "The web client's intent dispatcher. Renderers call `dispatch!` from
   their event handlers; the bus posts a transit-json envelope to the
   server and tags it with a UUID so the server can echo
   `:resolves-intent` back. Phase 1.C will also register the intent in
   a pending-intents table for optimistic prediction + reconciliation."
  (:require [cognitect.transit :as transit]))

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

(def ^:private writer (transit/writer :json))

(defn- t->str [v] (transit/write writer v))

(defn dispatch!
  "POST `{:intent intent :params params :id <uuid>}` to /intent. Returns
   the intent UUID."
  [intent params]
  (let [id (random-uuid)]
    (-> (js/fetch (str server-base "/intent")
                  #js {:method  "POST"
                       :headers #js {"Content-Type" "application/transit+json"}
                       :body    (t->str {:intent intent
                                         :params (or params {})
                                         :id     id})})
        (.catch (fn [e] (js/console.error "wun: intent failed" e))))
    id))
