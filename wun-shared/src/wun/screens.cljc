(ns wun.screens
  "Open screen registry. `defscreen` registers a screen spec keyed by
   namespaced keyword. Spec keys per the brief: `:path`, `:fetch`,
   `:render`. `:fetch` is server-only and runs before render; `:render`
   is a pure fn that takes the data the screen needs and returns a
   Hiccup-shaped tree using the component vocabulary.")

(defonce registry (atom {}))

(defn defscreen [k spec]
  (swap! registry assoc k spec)
  k)

(defn lookup [k] (get @registry k))

(defn render
  "Run the registered :render fn for screen `k` against `state`. Returns
   nil if the screen isn't registered."
  [k state]
  (when-let [{render-fn :render} (lookup k)]
    (render-fn state)))
