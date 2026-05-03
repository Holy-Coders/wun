(ns wun.forms.intents
  "Framework-level intents for form binding. Registered at namespace
   load like the rest of the `:wun/*` vocabulary; user code dispatches
   them with `:wun.forms/change`, `:wun.forms/touch`,
   `:wun.forms/submit`, `:wun.forms/reset`.

   The morphs are pure `(state, params) -> state`. Submit's handler
   side effects (network calls, DB writes) live behind the registered
   form spec's `:handler`, run server-side only by `wun.forms/run-submit`."
  (:require [wun.forms :as forms]
            [wun.intents :refer [defintent]]))

(defintent :wun.forms/change
  {:params [:map [:form keyword?] [:field keyword?] [:value any?]]
   :morph  (fn [state {:keys [form field value]}]
             (forms/change state form field value))})

(defintent :wun.forms/touch
  {:params [:map [:form keyword?] [:field keyword?]]
   :morph  (fn [state {:keys [form field]}]
             (forms/touch state form field))})

(defintent :wun.forms/reset
  {:params [:map [:form keyword?]]
   :morph  (fn [state {:keys [form]}]
             (forms/assoc-form state form (forms/empty-form)))})

;; Submit splits across both sides:
;;
;;   - Client (optimistic): just flip `:submitting?` true so the UI
;;     can dim the form and show a spinner. The handler doesn't run.
;;   - Server: `:server-only? false`, but the morph wraps `run-submit`
;;     which only does meaningful work on a server because the
;;     registered handler is server-resolved (e.g., DB calls).
;;
;; The handler runs identically on the client when registered as
;; pure -- which is the point. Apps that want side-effecting handlers
;; mark them server-only by NOT registering any handler in the cljc
;; reader-conditional cljs branch (and registering it in the clj
;; branch).

(defintent :wun.forms/submit
  {:params [:map [:form keyword?]]
   :morph  (fn [state {:keys [form]}]
             (forms/run-submit state form))})
