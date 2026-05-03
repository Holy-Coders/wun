(ns wun.web.meta
  "Apply a `:meta` map from a server envelope to the document head.
   Mirrors the role of LiveView's `assign(:page_title, …)` and
   Hotwire's head merging: the framework owns the small set of
   head-level fields it knows about; everything else stays
   user-editable in the static index.html.

   Fields we honour today:
     :title         -> document.title
     :description   -> <meta name=\"description\">
     :theme-color   -> <meta name=\"theme-color\">
     :og            -> map of OpenGraph keys, each as <meta property=\"og:*\">

   Tags we manage are tagged `data-wun-managed` so we don't trample
   anything the user added by hand."
  (:require [clojure.string :as str]))

(defn- ensure-meta-tag!
  [{:keys [name property] :as attrs}]
  (let [selector (cond
                   name     (str "meta[name='" name "'][data-wun-managed]")
                   property (str "meta[property='" property "'][data-wun-managed]"))
        existing (when selector (.querySelector js/document selector))]
    (or existing
        (let [el (.createElement js/document "meta")]
          (set! (.-dataset.wunManaged el) "true")
          (when name     (.setAttribute el "name"     name))
          (when property (.setAttribute el "property" property))
          (.appendChild (.-head js/document) el)
          el))))

(defn- set-meta!
  [attrs content]
  (let [el (ensure-meta-tag! attrs)]
    (if (some? content)
      (.setAttribute el "content" (str content))
      (some-> (.-parentNode el) (.removeChild el)))))

(defn apply-meta!
  "Idempotent: applies `meta` to the document head. Pass nil to leave
   everything alone (no-op rather than clear, so a transient absence
   doesn't blank the title)."
  [meta]
  (when (map? meta)
    (when-let [t (:title meta)]
      (set! (.-title js/document) t))
    (set-meta! {:name "description"} (:description meta))
    (set-meta! {:name "theme-color"} (:theme-color meta))
    (doseq [[k v] (:og meta)]
      (set-meta! {:property (str "og:" (name k))} v))))

;; ---------------------------------------------------------------------------
;; Theme application: write the effective theme as CSS custom properties
;; on the document root so plain CSS can reference them via
;; `var(--wun-color-primary)`. Renderers that resolve tokens at
;; hiccup-build time still work; this hook is for plain CSS in
;; index.html that wants to participate in the theme.

(defn- token->css-var
  "Convert a namespaced keyword token to a CSS custom property name.
   `:wun.color/primary` -> `--wun-color-primary`. Anything that's not
   a namespaced keyword returns nil (we skip it)."
  [k]
  (when (and (keyword? k) (namespace k))
    (str "--" (-> (namespace k) (.replaceAll "\\." "-"))
         "-"  (-> (name k)      (.replaceAll "\\." "-")))))

(defn- ->css-value [v]
  (cond
    (string? v) v
    (number? v) (str v "px")
    :else       (str v)))

(defn apply-theme!
  "Apply the effective theme as CSS custom properties on
   document.documentElement. Idempotent: re-applying with a smaller
   theme strips the no-longer-present tokens so dark-mode toggles or
   per-screen overrides don't leak across screens."
  [theme]
  (let [root  (.-documentElement js/document)
        style (.-style root)
        ;; Track the names we just wrote so a subsequent apply-theme!
        ;; with fewer tokens removes the orphans.
        prev  (or (some-> root .-dataset .-wunThemeKeys
                          (.split ","))
                  #js [])]
    (when (map? theme)
      (let [now (reduce-kv (fn [acc k v]
                             (if-let [css-name (token->css-var k)]
                               (do (.setProperty style css-name (->css-value v))
                                   (conj acc css-name))
                               acc))
                           []
                           theme)
            now-set (set now)]
        ;; Strip tokens from the prior set that aren't in the new one.
        (doseq [name prev]
          (when (and (string? name)
                     (not (contains? now-set name)))
            (.removeProperty style name)))
        (set! (.. root -dataset -wunThemeKeys)
              (str/join "," now))))))
