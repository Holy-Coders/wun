(ns wun.web.renderers
  "Open web renderer registry. Each component keyword maps to a fn
   `(props children-as-replicant-hiccup) -> replicant-hiccup`. Framework
   code and user code register through `register!` identically; the
   registry doesn't distinguish the two.

   `render-node` walks an incoming Wun Hiccup tree (which uses
   namespaced component keywords like :wun/Stack) and produces a
   Replicant Hiccup tree (which uses plain HTML keywords like :div).
   Replicant's renderer then handles the actual DOM updates -- moving
   from React/Reagent to Replicant doesn't change the registry
   contract, only the rendering substrate underneath. No more Virtual
   DOM Through React; Replicant computes a minimal patch list against
   the prior render and emits direct DOM operations.")

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
  "Wun Hiccup -> Replicant Hiccup. Strings and numbers pass through as
   text. Vectors with a keyword head dispatch through the registry;
   unknown components fall back to a visible placeholder. Lists become
   inline child sequences (Replicant flattens nested seqs natively, so
   no fragment wrapper needed -- unlike React/Reagent which required
   the `[:<>]` fragment tag)."
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
        ;; Plain seq -> flatten into a fragment-shaped vector. Replicant
        ;; accepts seqs of children directly, so we just splice.
        (vec (render-children node))))
    :else (str node)))
