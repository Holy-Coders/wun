(ns wun.diff
  "Pure tree differ + path-aware patch applicator.

   Patches are JSON-Patch-flavoured maps:
     {:op :replace :path [...] :value <hiccup>}
     {:op :insert  :path [...] :value <hiccup>}
     {:op :remove  :path [...]}

   Path elements are integer child indices. A path of `[]` addresses the
   whole tree. Hiccup vectors carry their tag at vector index 0 and an
   optional props map at index 1; child paths skip those slots, so
   `[0]` is the first child *after* tag/props, `[1 0]` is the first
   child of that, and so on.

   Phase 1.B does naive position-keyed diffing only. When a component's
   tag and props are unchanged we recurse into children; otherwise we
   emit a `:replace` for the whole subtree. Phase 1.D adds prop-level
   diffing and key-aware reordering.

   Both `diff` (server-side emission) and `apply-patches`
   (client-side, and eventually server-side for optimistic
   reconciliation) live here so the algorithm can't drift between
   producer and consumer.")

;; ---------------------------------------------------------------------------
;; Hiccup helpers

(defn- component-vec? [x]
  (and (vector? x) (keyword? (first x))))

(defn- has-props? [v]
  (and (vector? v) (>= (count v) 2) (map? (second v))))

(defn- props-of [v] (when (has-props? v) (second v)))

(defn- children-of [v]
  (if (has-props? v) (drop 2 v) (rest v)))

(defn- child-offset [v]
  (if (has-props? v) 2 1))

;; ---------------------------------------------------------------------------
;; Diff

(declare diff-children)

(defn diff
  "Produce a vector of patches that turn `old` into `new` at `path`.
   Returns `[]` when the trees are equal."
  ([old new] (diff old new []))
  ([old new path]
   (cond
     (= old new)
     []

     (and (component-vec? old) (component-vec? new)
          (= (first old)   (first new))
          (= (props-of old) (props-of new)))
     (diff-children (children-of old) (children-of new) path)

     :else
     [{:op :replace :path path :value new}])))

(defn- diff-children [olds news path]
  (let [olds  (vec olds)
        news  (vec news)
        n-old (count olds)
        n-new (count news)
        n     (min n-old n-new)
        common  (mapcat (fn [i] (diff (nth olds i) (nth news i) (conj path i)))
                        (range n))
        inserts (map (fn [i] {:op :insert :path (conj path i) :value (nth news i)})
                     (range n n-new))
        ;; Remove from the highest index down so each :remove path stays
        ;; valid as the list shrinks.
        removes (map (fn [i] {:op :remove :path (conj path i)})
                     (reverse (range n n-old)))]
    (vec (concat common inserts removes))))

;; ---------------------------------------------------------------------------
;; Apply

(defn- replace-child [v i value]
  (assoc v (+ i (child-offset v)) value))

(defn- get-child [v i]
  (nth v (+ i (child-offset v))))

(defn- insert-child [v i value]
  (let [vec-idx (+ i (child-offset v))
        before  (subvec v 0 vec-idx)
        after   (subvec v vec-idx)]
    (vec (concat before [value] after))))

(defn- remove-child [v i]
  (let [vec-idx (+ i (child-offset v))]
    (vec (concat (subvec v 0 vec-idx) (subvec v (inc vec-idx))))))

(defn- update-at-path [tree path f]
  (if (empty? path)
    (f tree)
    (let [[head & rest-path] path]
      (replace-child tree head
                     (update-at-path (get-child tree head) (vec rest-path) f)))))

(defn apply-patch
  "Return a new tree that is `tree` with `patch` applied. `:replace` at
   `[]` returns the patch's `:value` outright; `:insert` and `:remove`
   require a non-empty path."
  [tree {:keys [op path value]}]
  (case op
    :replace
    (if (empty? path)
      value
      (let [parent (vec (butlast path))
            idx    (last path)]
        (update-at-path tree parent #(replace-child % idx value))))

    :insert
    (let [parent (vec (butlast path))
          idx    (last path)]
      (update-at-path tree parent #(insert-child % idx value)))

    :remove
    (let [parent (vec (butlast path))
          idx    (last path)]
      (update-at-path tree parent #(remove-child % idx)))

    ;; Unknown op: leave tree unchanged.
    tree))

(defn apply-patches
  "Sequentially fold `patches` over `tree`."
  [tree patches]
  (reduce apply-patch tree patches))
