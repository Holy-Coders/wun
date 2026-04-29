(ns wun.server.intents)

;; Phase 0 intent registry. The full `definent` per the brief carries
;; :params, :morph, :loading, :on-error, :optimistic?, :authorize, :persist.
;; For the spike we only honour :morph -- a (state, params) -> state fn.
;;
;; The brief eventually wants morph to return *patches* and run on both
;; server and client (.cljc). Phase 0 stays whole-state-in / whole-state-out
;; since the wire only emits full-tree :replace at root anyway.

(defonce registry (atom {}))

(defn register-intent! [intent-name spec]
  (swap! registry assoc intent-name spec)
  intent-name)

(defmacro definent
  "Phase 0 stub of the user-facing macro. Registers an intent spec under
   `intent-name`. See the project brief for the full spec shape."
  [intent-name spec]
  `(register-intent! ~intent-name ~spec))

(defn lookup [intent-name]
  (get @registry intent-name))

(defn apply-intent
  "Run the morph for `intent-name` against `state` with `params`.
   Returns the new state. Unknown intents are a no-op (logged by caller)."
  [state intent-name params]
  (if-let [{:keys [morph]} (lookup intent-name)]
    (morph state params)
    state))

;; ---------------------------------------------------------------------------
;; Default intents shipped with the spike.
(defn register-defaults! []
  (definent :counter/inc
    {:params [:map]
     :morph  (fn [state _params]
               (update state :counter (fnil inc 0)))})

  (definent :counter/dec
    {:params [:map]
     :morph  (fn [state _params]
               (update state :counter (fnil dec 0)))})

  (definent :counter/reset
    {:params [:map]
     :morph  (fn [state _params]
               (assoc state :counter 0))}))
