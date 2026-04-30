(ns wun.web.renderers
  "Open web renderer registry. Each component keyword maps to a fn
   `(props children-as-reagent-hiccup) -> reagent-hiccup`. Framework
   code and user code register through `register!` identically; the
   registry doesn't distinguish the two.

   `render-node` walks an incoming Wun Hiccup tree (which uses
   namespaced component keywords like :wun/Stack) and produces a
   reagent Hiccup tree (which uses plain HTML keywords like :div).
   Reagent's React reconciler then handles the actual DOM updates -- so
   moving from the previous hand-rolled vanilla-DOM applicator to
   reagent doesn't change the registry contract, only what the
   registered fn returns.")

(defonce registry (atom {}))

(defn register!
  "Register `f` as the web renderer for component keyword `k`.
   Returns `k`."
  [k f]
  (swap! registry assoc k f)
  k)

(defn lookup [k] (get @registry k))

(defn registered [] (sort (keys @registry)))

(declare render-node)

(defn- props+children [v]
  (let [maybe-props (second v)]
    (if (map? maybe-props)
      [maybe-props (drop 2 v)]
      [{}          (rest v)])))

(defn- render-children [children]
  (mapv render-node children))

(defn render-node
  "Wun Hiccup -> reagent Hiccup. Strings, numbers, and booleans pass
   through as text. Vectors with a keyword head dispatch through the
   registry; unknown components fall back to a visible placeholder
   (phase-2 swaps that for a Hotwire WebFrame fallback)."
  [node]
  (cond
    (nil? node)     nil
    (string? node)  node
    (number? node)  (str node)
    (boolean? node) (str node)
    (vector? node)
    (let [tag (first node)]
      (if (keyword? tag)
        (let [[props children] (props+children node)
              kids             (render-children children)
              f                (lookup tag)]
          (when-not f
            (js/console.warn "wun: no renderer for tag"
                             (pr-str tag)
                             "registered:"
                             (clj->js (mapv pr-str (registered)))))
          (if f
            (f props kids)
            (into [:div.wun-unknown {} (str "[unknown component " tag "]")] kids)))
        ;; Plain seq -> reagent fragment.
        (into [:<>] (render-children node))))
    :else (str node)))
