(ns wun.theme
  "Theme primitives: a flat registry of namespaced design tokens
   (colours, spacing, typography, radii, shadows, ...) that cascade
   from server to every client through the wire and resolve to
   platform-native values at render time.

   Why themes are server-driven: a Wun deployment is a single program
   describing a UI for four targets. Hard-coding `#0a66c2` in a
   web renderer and `Color(red: 10, green: 102, blue: 194)` in an
   iOS renderer is the four-versions-of-the-same-thing problem the
   framework exists to avoid. Tokens are declared once and resolved
   per platform.

   Token shape: a flat map of namespaced keyword -> value.

       {:wun.color/primary    \"#0a66c2\"
        :wun.color/text       \"#111\"
        :wun.spacing/sm       8
        :wun.spacing/md       16
        :wun.font/body-size   14
        :wun.radius/md        8}

   Apps add their own tokens in their own namespace
   (`:myapp.color/brand`, `:myapp.font/display`); the registry is
   open like every other Wun registry.

   Cascade priority (highest wins):
     1. Per-conn override     -- app state `:theme/overrides` map
     2. Per-screen override   -- screen spec `:theme` map
     3. App default           -- registered via `set-default!`
     4. Framework default     -- the empty map (no tokens)

   Resolution is pure: `(resolve theme prop-map)` walks each value and
   substitutes any namespaced keyword that the theme knows about. Any
   keyword the theme doesn't know is passed through unchanged so a
   genuinely-keyword value (`:row` for direction, `:h1` for variant)
   survives.

   The theme rides on the wire as the envelope's `:theme` field so the
   client can resolve tokens optimistically without waiting for a round
   trip. Clients that ignore the field render with literal token
   keywords as values, which most renderers will visibly ignore -- a
   user sees the missing tokens immediately rather than a silent
   visual bug."
  (:require [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; Registry

(defonce ^:private app-default (atom {}))

(defn set-default!
  "Register the app-level default theme. Subsequent calls replace
   the theme wholesale (apps that compose multiple sources should
   merge themselves before calling). Returns the theme."
  [theme]
  (reset! app-default theme))

(defn merge-default!
  "Like `set-default!` but merges into the current app-level theme
   instead of replacing it."
  [theme]
  (swap! app-default merge theme))

(defn default-theme [] @app-default)

;; ---------------------------------------------------------------------------
;; Cascade

(defn cascade
  "Compose the effective theme for a render: framework < app default
   < screen override < conn override. Each layer is a flat token map;
   later layers replace per-key earlier layers."
  ([state] (cascade state nil))
  ([state screen-theme]
   (merge {}
          @app-default
          (or screen-theme {})
          (or (:theme/overrides state) {}))))

;; ---------------------------------------------------------------------------
;; Resolution

(defn token?
  "True when `v` is a namespaced keyword (the only shape we treat as
   a theme reference). Plain keywords (`:row`, `:h1`) are values
   themselves and are NOT tokens."
  [v]
  (and (keyword? v) (some? (namespace v))))

(defn resolve-value
  "Resolve `v` against `theme`. Tokens that match a registered key
   are substituted; tokens that don't are left in place so a
   misspelled token name shows up visibly rather than silently
   becoming nil."
  [theme v]
  (if (and (token? v) (contains? theme v))
    (get theme v)
    v))

(defn- resolve-props [theme props]
  (reduce-kv (fn [m k v]
               (assoc m k (resolve-value theme v)))
             {} props))

(defn- has-props? [v]
  (and (vector? v) (>= (count v) 2) (map? (second v))))

(defn resolve-tree
  "Walk `tree`, resolving every prop-value against `theme`. Children
   are recursed unconditionally so deep trees with nested theme
   references all see the same theme. Strings, numbers, and non-
   keyword-headed vectors pass through unchanged."
  [theme tree]
  (cond
    (not (vector? tree)) tree

    (not (keyword? (first tree)))
    (mapv #(resolve-tree theme %) tree)

    :else
    (let [tag      (first tree)
          props    (when (has-props? tree) (second tree))
          children (if (has-props? tree) (drop 2 tree) (rest tree))]
      (into (cond-> [tag] props (conj (resolve-props theme props)))
            (map #(resolve-tree theme %) children)))))

;; ---------------------------------------------------------------------------
;; Convenience

(defn token
  "Lookup a single token in `theme`. Returns nil for missing tokens."
  [theme k]
  (get theme k))
