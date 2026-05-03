(ns myapp.server.main
  "Server entry point. Loading the side-effecting registry namespaces
   here is what turns this app's intents / screens / components into
   live registrations against the framework's open registries.

   Run via `clojure -M:server` (alias defined in ../deps.edn at the
   project root)."
  (:require [wun.server.http :as http]
            ;; Framework foundations -- the :wun/* component vocabulary.
            wun.foundation.components
            ;; App registries -- side-effecting requires populate them.
            myapp.components
            myapp.intents
            myapp.screens)
  (:gen-class))

(defn -main [& _]
  ;; PORT / HOST are honored by http/start! itself (env -> :port/:host).
  ;; Passing them explicitly here is redundant; we keep this lean so the
  ;; default scaffold stays a one-liner.
  (http/start! {:static "public"})
  @(promise))
