(ns wun.web.renderers
  "Open web renderer registry and the Hiccup -> DOM driver. Each
   component keyword maps to a fn `(props children-dom-nodes) -> dom-node`.
   Framework code and user code register through `register!` identically.

   `render-node` walks an incoming Hiccup tree, recursively materialising
   children first, then dispatching to the registered renderer for the
   tag. Anything not in the registry falls through to a visible
   placeholder; phase 2 swaps that for a Hotwire WebFrame fallback.")

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
  (->> children
       (map render-node)
       (filter some?)
       vec))

(defn- placeholder [tag children]
  (let [n (.createElement js/document "div")]
    (set! (.-className n) "wun-unknown")
    (set! (.-textContent n) (str "[unknown component " tag "]"))
    (doseq [c (render-children children)] (.appendChild n c))
    n))

(defn render-node
  "Hiccup-shaped node -> DOM node. Strings and numbers become text
   nodes; vectors with a keyword head dispatch through the registry."
  [node]
  (cond
    (nil? node)     nil
    (string? node)  (.createTextNode js/document node)
    (number? node)  (.createTextNode js/document (str node))
    (boolean? node) (.createTextNode js/document (str node))
    (vector? node)
    (let [tag (first node)]
      (if (keyword? tag)
        (let [[props children] (props+children node)
              kids (render-children children)]
          (if-let [f (lookup tag)]
            (f props kids)
            (placeholder tag children)))
        ;; Plain seq -> wrap in a fragment-y div.
        (let [n (.createElement js/document "div")]
          (.setAttribute n "data-wun-fragment" "")
          (doseq [c (render-children node)] (.appendChild n c))
          n)))
    :else (.createTextNode js/document (str node))))

(defn mount-tree!
  "Replace the contents of `^js root-el` with the rendered `tree`."
  [^js root-el tree]
  (set! (.-innerHTML root-el) "")
  (when-some [n (render-node tree)]
    (.appendChild root-el n)))
