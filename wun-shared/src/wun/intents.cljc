(ns wun.intents
  "Open intent registry + apply-intent. Shared between server (where the
   morph runs as authoritative state transition) and any platform that
   wants optimistic prediction (web today; iOS/Android in phase 4 once
   SCI bundling settles).

   Spec keys per the brief: `:params`, `:morph`, `:loading`, `:on-error`,
   `:optimistic?`, `:authorize`, `:persist`. `:authorize` and `:persist`
   are server-only. Phase 1.A only honours `:morph`; the rest land in
   later slices.")

(defonce registry (atom {}))

(defn definent [k spec]
  (swap! registry assoc k spec)
  k)

(defn lookup [k] (get @registry k))

(defn apply-intent
  "Run the morph for `intent-name` against `state` with `params`. Returns
   the new state. Unknown intents leave state unchanged."
  [state intent-name params]
  (if-let [{:keys [morph]} (lookup intent-name)]
    (morph state params)
    state))
