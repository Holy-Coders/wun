(ns wun.components
  "Open component registry. `defcomponent` registers a component spec
   keyed by namespaced keyword. The registry is open: framework code and
   user code both register through this same fn -- there is no
   privileged path. `:wun/Stack` and `:myapp/RichEditor` are
   indistinguishable to the runtime.

   Renderers are registered separately on the platform that owns them.
   See `wun.web.renderers` on the web side; iOS/Android land in phases
   2 and 3 with their own registries.

   Note: `defcomponent` is currently a fn, not a macro. The user-facing
   shape is identical to the macro form in the brief; the fn lifts in
   phase 1.D once Malli (Clojars) lands and we want compile-time schema
   validation.")

(defonce registry (atom {}))

(defn defcomponent
  "Register a component spec under `k`. Returns `k`. Spec keys per the
   brief: `:since`, `:schema`, `:loading`, `:fallback`, `:ios`,
   `:android`, plus an inline `:web` renderer when the file is read as
   cljs (use a reader conditional). Unknown keys are preserved verbatim."
  [k spec]
  (swap! registry assoc k spec)
  k)

(defn lookup [k] (get @registry k))

(defn registered [] (sort (keys @registry)))
