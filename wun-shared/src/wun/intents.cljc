(ns wun.intents
  "Open intent registry, Malli-validated params, and the shared
   `apply-intent` morph dispatcher. Runs identically on server (where
   it mutates authoritative app state) and on the web client (where it
   predicts optimistically against a confirmed-state mirror). The
   shared `.cljc` location is what makes 'one function, two execution
   sites' possible -- producer and consumer cannot drift.

   Spec keys per the brief: `:params`, `:morph`, `:loading`,
   `:on-error`, `:optimistic?`, `:authorize`, `:persist`. Phase 1.C/D
   honours `:params` (schema) and `:morph`; the rest land in later
   slices. `:authorize` and `:persist` are server-only by definition."
  (:require [malli.core  :as m]
            [malli.error :as me]))

(defonce registry (atom {}))

(defn definent [k spec]
  (swap! registry assoc k spec)
  k)

(defn lookup [k] (get @registry k))

;; ---------------------------------------------------------------------------
;; Param validation

(defn validate-params
  "Validate `params` against the intent's `:params` schema. Returns nil
   on success, or a humanised explanation map on failure (suitable
   for printing to a log or echoing to the client)."
  [intent-name params]
  (when-let [{schema :params} (lookup intent-name)]
    (when (and schema (not (m/validate schema params)))
      {:intent      intent-name
       :params      params
       :explanation (me/humanize (m/explain schema params))})))

;; ---------------------------------------------------------------------------
;; Morph dispatch

(defn apply-intent
  "Run the morph for `intent-name` against `state` with `params`.
   Returns the new state. Validation failures and unknown intents
   leave state unchanged; callers that want to know the difference
   (server's HTTP handler, client's optimistic predictor) should call
   `validate-params` and `lookup` themselves first."
  [state intent-name params]
  (let [{:keys [morph]} (lookup intent-name)]
    (cond
      (nil? morph) state
      (validate-params intent-name params) state
      :else (morph state params))))
