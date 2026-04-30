(ns wun.screens
  "Open screen registry. `defscreen` registers a screen spec keyed by
   namespaced keyword. Spec keys per the brief: `:path`, `:fetch`,
   `:render`. `:fetch` is server-only and runs before render; `:render`
   is a pure fn that takes the data the screen needs and returns a
   Hiccup-shaped tree using the component vocabulary.

   `:meta` (added in 7.A, parallel to LiveView `assign(:page_title …)`
   and Hotwire's `<head>` merging) is an optional fn `(state) -> map`
   returning cross-platform metadata: at minimum `:title`, optionally
   `:description`, `:theme-color`, and `:og` (web-only OpenGraph).
   The framework diffs meta per-connection so unchanged meta doesn't
   ride the wire on every patch.")

(defonce registry (atom {}))

(defn defscreen [k spec]
  (swap! registry assoc k spec)
  k)

(defn lookup [k] (get @registry k))

(defn lookup-by-path
  "Return the screen key whose :path matches `path`, or nil. Phase
   1.G uses literal path matching only; phase-2 routing with path
   params (`/users/:id`) will need a real router."
  [path]
  (some (fn [[k spec]]
          (when (= path (:path spec)) k))
        @registry))

(defn render
  "Run the registered :render fn for screen `k` against `state`. Returns
   nil if the screen isn't registered."
  [k state]
  (when-let [{render-fn :render} (lookup k)]
    (render-fn state)))

(defn render-meta
  "Run the registered :meta fn for screen `k` against `state`, or
   return nil if the screen has no :meta. Returned map travels in the
   envelope's `:meta` field; clients apply it to platform-specific
   surfaces (document.title, NavigationView title, etc.)."
  [k state]
  (when-let [{meta-fn :meta} (lookup k)]
    (when meta-fn (meta-fn state))))
