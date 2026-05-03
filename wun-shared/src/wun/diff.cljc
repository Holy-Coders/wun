(ns wun.diff
  "Pure tree differ + path-aware patch applicator.

   Patches are JSON-Patch-flavoured maps:
     {:op :replace  :path [...] :value <hiccup>}
     {:op :insert   :path [...] :value <hiccup>}
     {:op :remove   :path [...]}
     {:op :children :path [...] :order [{:key k :existing? true|false :value <hiccup>?}...]}

   Path elements are integer child indices. A path of `[]` addresses the
   whole tree. Hiccup vectors carry their tag at vector index 0 and an
   optional props map at index 1; child paths skip those slots, so
   `[0]` is the first child *after* tag/props, `[1 0]` is the first
   child of that, and so on.

   Position-keyed diff (the v1 default) recurses into children when a
   component's tag and props are unchanged; otherwise it emits a
   `:replace` for the whole subtree. Reorders re-render every sibling
   past the first changed index because indices shift.

   Wire v2 adds **key-aware** diffing: when every child of a parent
   carries a `:key` prop, the differ matches old↔new by key rather
   than position. The single `:children` op replays the new key
   ordering on the client, reusing existing keyed children and
   inserting fresh subtrees inline only for keys that didn't exist
   in old. After the topology op, recursive diffs into kept children
   handle their prop/text changes.

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
;; Key-aware support

(defn- key-of
  "Returns the `:key` prop of a component vector, or nil. Strings,
   numbers, and propless vectors all return nil."
  [v]
  (when (component-vec? v)
    (:key (props-of v))))

(defn- all-keyed?
  "True when every element of `xs` is a component vector carrying a
   `:key` prop. Empty collections are NOT keyed (we have nothing to
   key against)."
  [xs]
  (and (seq xs)
       (every? key-of xs)))

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

(defn- diff-children-positional [olds news path]
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

(defn- diff-children-keyed
  "Wire-v2 key-aware children diff. Emits a single `:children` op that
   carries the new key ordering plus inline subtrees for any keys that
   didn't exist in `olds`, followed by recursive diffs into the kept
   keys' children. The apply side reuses existing keyed children
   wherever possible -- the wire payload stays small even for large
   reorders, and child state on platforms that have such a thing
   (focus, scroll position) survives the rearrangement."
  [olds news path]
  (let [old-vec    (vec olds)
        new-vec    (vec news)
        old-by-key (into {} (map (fn [c] [(key-of c) c]) old-vec))
        order      (mapv (fn [c]
                           (let [k (key-of c)]
                             (if (contains? old-by-key k)
                               {:key k :existing? true}
                               {:key k :existing? false :value c})))
                         new-vec)
        kept-keys  (filter old-by-key (map key-of new-vec))
        ;; After the :children op the client's children list has the
        ;; new ordering; recurse into each kept key at its new index.
        new-index-of-key (into {} (map-indexed (fn [i c] [(key-of c) i]) new-vec))
        recursive  (mapcat (fn [k]
                             (diff (get old-by-key k)
                                   (nth new-vec (new-index-of-key k))
                                   (conj path (new-index-of-key k))))
                           kept-keys)
        topology   (when (not= (mapv key-of old-vec) (mapv key-of new-vec))
                     [{:op :children :path path :order order}])]
    (vec (concat topology recursive))))

;; Negotiation knob: wire v1 disables key-aware children diffing so an
;; old client that doesn't understand the `:children` op stays in sync.
;; Bound by the HTTP layer per-broadcast based on client capability.
(def ^:dynamic *envelope-version* 2)

(defn- diff-children [olds news path]
  (if (and (>= *envelope-version* 2)
           (all-keyed? olds)
           (all-keyed? news))
    (diff-children-keyed olds news path)
    (diff-children-positional olds news path)))

(defn diff-v1
  "Position-only diff for clients negotiated to wire v1. Never emits
   `:children` ops; reorders fall back to per-position `:replace`s.
   Useful in tests and any code that needs the v1 algorithm without
   threading the dynamic var manually."
  ([old new] (diff-v1 old new []))
  ([old new path]
   (binding [*envelope-version* 1]
     (diff old new path))))

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

(defn- replay-children-order
  "Wire-v2 :children op. `parent` is the parent component vector; rebuild
   its children using `order` (a vector of `{:key :existing?
   :value?}`). Existing keyed children are looked up in the current
   children list and reused; new ones come from the inline `:value`."
  [parent order]
  (let [current  (children-of parent)
        by-key   (into {} (map (fn [c] [(key-of c) c]) current))
        rebuilt  (mapv (fn [{:keys [key existing? value]}]
                         (if existing?
                           (or (get by-key key)
                               ;; Defensive: client thought the key was
                               ;; existing but the local tree has lost
                               ;; it. Fall back to the inline value if
                               ;; one was sent; otherwise drop.
                               value)
                           value))
                       order)
        head     (subvec parent 0 (child-offset parent))]
    (into head rebuilt)))

(defn apply-patch
  "Return a new tree that is `tree` with `patch` applied. `:replace` at
   `[]` returns the patch's `:value` outright; `:insert`, `:remove`, and
   `:children` require a non-empty path or address the root."
  [tree {:keys [op path value order] :as _patch}]
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

    :children
    (if (empty? path)
      (replay-children-order tree order)
      (update-at-path tree path #(replay-children-order % order)))

    ;; Unknown op: leave tree unchanged.
    tree))

(defn apply-patches
  "Sequentially fold `patches` over `tree`."
  [tree patches]
  (reduce apply-patch tree patches))
