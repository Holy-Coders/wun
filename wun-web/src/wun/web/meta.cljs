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
   anything the user added by hand.")

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
