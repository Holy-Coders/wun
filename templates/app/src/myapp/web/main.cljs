(ns myapp.web.main
  "Web entry point. shadow-cljs.edn names this ns's `init` as the
   bundle's init-fn. We delegate to wun.web.core/init for the SSE +
   reagent wiring, but first require every namespace whose load-time
   side effects populate the open registries."
  (:require [wun.web.core      :as wun]
            [wun.web.foundation]            ; :wun/* renderers
            [wun.foundation.components]      ; :wun/* component specs
            ;; -- this app's registries --
            [myapp.components]
            [myapp.intents]
            [myapp.screens]
            [myapp.web.renderers]))

(defn ^:export init []
  (wun/init))

(defn ^:export after-reload []
  (wun/after-reload))
