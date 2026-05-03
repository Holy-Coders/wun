(ns wun.forms
  "Form binding primitives. The killer feature LiveView ships -- a
   declarative `:wun/Form` whose field values, errors, touched-set,
   and submission state live in app state next to the morphs that
   mutate them.

   State shape, parked under `[:forms <form-id>]` in app state:

       {:values     {:email \"x@y.com\" :password \"\"}
        :errors     {:email   nil
                     :password \"required\"}
        :touched    #{:email}
        :submitting? false
        :submitted?  false}

   Lifecycle:

   1. The user types into a `:wun/Field`. The optimistic morph fires
      a `:wun.forms/change` intent that updates `:values` and adds
      the field to `:touched`. The server runs the same morph; the
      values converge.

   2. The user submits. A `:wun.forms/submit` intent fires; the morph
      sets `:submitting? true` and calls the registered handler (a
      pure fn `(state, values) -> [state outcome]`) once on the
      server. The handler runs schema validation; on failure the
      `:errors` map populates and the form re-renders. On success
      the handler returns `[state {:status :ok ...}]` and the form
      sets `:submitted? true`.

   3. App-level intents triggered as a side effect of the handler
      (navigation, persistence) ride out through the normal intent
      pipeline.

   The schemas are Malli; Malli is shared with `wun.intents`. The
   validation result is humanised via `malli.error/humanize` so the
   client can render error strings directly. We also expose
   `(field-error state form-id field)` and friends so renderers
   don't have to drill into the form-state shape themselves."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Open registry

(defonce registry (atom {}))

(defn defform
  "Register a form spec under `id`. Spec keys:

     :id        the namespaced keyword identifying the form
     :schema    Malli schema for the form's :values
     :handler   `(state, values) -> [new-state outcome]`. Outcome is
                a map with `:status :ok|:error` plus optional
                `:errors` (overrides validation errors) and `:effects`
                (side effects to fire after submit).

   Idempotent: re-registering the same id replaces the spec (handy
   at the REPL)."
  [id spec]
  (swap! registry assoc id (assoc spec :id id))
  id)

(defn lookup [id] (get @registry id))

;; ---------------------------------------------------------------------------
;; Pure state helpers

(defn empty-form
  "Initial form state. App-level init code can pre-populate by
   merging values into `(empty-form)`."
  []
  {:values      {}
   :errors      {}
   :touched     #{}
   :submitting? false
   :submitted?  false})

(defn form-state
  "Look up a form's state in `state`, defaulting to `empty-form`."
  [state form-id]
  (or (get-in state [:forms form-id]) (empty-form)))

(defn assoc-form
  "Replace the state slice for `form-id`."
  [state form-id form]
  (assoc-in state [:forms form-id] form))

(defn update-form
  "Apply `f` to `form-id`'s state slice."
  [state form-id f & args]
  (assoc-in state [:forms form-id]
            (apply f (form-state state form-id) args)))

;; ---------------------------------------------------------------------------
;; Public API

(defn change
  "Set `field` to `value` in `form-id`. Marks the field touched and
   clears any error attached to that field; the next submit
   re-validates from scratch."
  [state form-id field value]
  (update-form state form-id
               (fn [f]
                 (-> f
                     (assoc-in  [:values field] value)
                     (update    :touched conj field)
                     (update    :errors dissoc field)))))

(defn touch
  "Mark `field` as touched without changing its value (e.g. on blur)."
  [state form-id field]
  (update-form state form-id update :touched conj field))

(defn validate
  "Validate `form-id`'s current values against its registered schema.
   Returns a map of field -> humanised error message, or `{}` when
   valid. Form ids without a registered schema validate clean."
  [state form-id]
  (if-let [{schema :schema} (lookup form-id)]
    (let [values (:values (form-state state form-id))]
      (if (m/validate schema values)
        {}
        (or (me/humanize (m/explain schema values)) {})))
    {}))

(defn begin-submit
  "Mark `form-id` as submitting; clear prior errors. Optimistic --
   runs on both client and server."
  [state form-id]
  (update-form state form-id
               (fn [f]
                 (assoc f :submitting? true :errors {}))))

(defn finish-submit
  "Apply `outcome` (a `{:status :ok|:error :errors? ... :state? ...}`)
   to `form-id`. On `:error` the errors map is set and submitting
   flips back to false; on `:ok` `submitted?` is true and errors are
   cleared. Server-only -- the client predicts `begin-submit` only
   and waits for the server's authoritative outcome."
  [state form-id outcome]
  (update-form state form-id
               (fn [f]
                 (case (:status outcome)
                   :ok    (assoc f
                                 :submitting? false
                                 :submitted?  true
                                 :errors      {})
                   :error (assoc f
                                 :submitting? false
                                 :errors      (or (:errors outcome) {}))
                   ;; Unknown status: leave form alone.
                   f))))

(defn run-submit
  "Server-side: validate `form-id`, run its registered handler if
   valid, and merge the outcome into the form. The handler signature
   is `(state, values) -> [new-state outcome]`. Returns the new state
   with the outcome already merged. Anonymous forms (no schema, no
   handler) are a no-op outside of `begin-submit` -- the form just
   stays in the submitting state until an app-level intent calls
   `finish-submit` itself."
  [state form-id]
  (let [{:keys [handler schema]} (lookup form-id)
        values (:values (form-state state form-id))
        errors (validate state form-id)
        state' (begin-submit state form-id)]
    (cond
      (seq errors)
      (finish-submit state' form-id {:status :error :errors errors})

      handler
      (let [[state'' outcome] (handler state' values)]
        (finish-submit state'' form-id outcome))

      :else
      state')))

;; ---------------------------------------------------------------------------
;; Read-only accessors so renderers don't drill the shape themselves.

(defn field-value [state form-id field]
  (get-in state [:forms form-id :values field]))

(defn field-error [state form-id field]
  (get-in state [:forms form-id :errors field]))

(defn field-touched? [state form-id field]
  (contains? (get-in state [:forms form-id :touched] #{}) field))

(defn submitting? [state form-id]
  (boolean (get-in state [:forms form-id :submitting?])))

(defn submitted? [state form-id]
  (boolean (get-in state [:forms form-id :submitted?])))
