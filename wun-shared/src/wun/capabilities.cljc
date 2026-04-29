(ns wun.capabilities
  "Capability negotiation. The client tells the server which components
   it can render (and at what versions); the server walks the
   post-render tree and substitutes `[:wun/WebFrame {...}]` for any
   subtree the client cannot render natively. Substitution is at the
   smallest containing subtree.

   Wire format on the URL (EventSource doesn't allow custom headers):
     /wun?caps=wun/Stack@1,wun/Text@1,wun/Button@1,wun/WebFrame@1

   The brief calls for an X-Wun-Capabilities HTTP header for native
   clients; phase 2 (iOS/Android) plumbs that. Web stays on the query
   string."
  (:require [clojure.string :as str]
            [wun.components :as components]))

;; ---------------------------------------------------------------------------
;; Wire serialisation

(defn- piece->entry [s]
  (let [m (re-matches #"\s*([^/\s]+)/([^@\s]+)@(\d+)\s*" (or s ""))]
    (when m
      (let [[_ ns- name- v] m]
        [(keyword ns- name-) #?(:clj (Long/parseLong v) :cljs (js/parseInt v 10))]))))

(defn parse
  "Turn `\"wun/Stack@1,wun/Text@2,...\"` into `{:wun/Stack 1 :wun/Text 2 ...}`.
   Anything malformed is dropped silently; an absent capability list
   parses to an empty map."
  [s]
  (if (string? s)
    (into {} (keep piece->entry (str/split s #",")))
    {}))

(defn serialize
  "Inverse of parse. Stable order so callers see deterministic output."
  [caps]
  (->> caps
       (sort-by (comp str key))
       (map (fn [[k v]] (str (namespace k) "/" (name k) "@" v)))
       (str/join ",")))

;; ---------------------------------------------------------------------------
;; Predicate

(defn supported?
  "True when `caps` lists `k` at a version >= `k`'s registered :since.
   Unknown components (not in the framework registry) are treated as
   :since 1 -- user code that hasn't called `defcomponent` shouldn't
   block the negotiator."
  [caps k]
  (when-let [client-version (get caps k)]
    (let [since (or (:since (components/lookup k)) 1)]
      (>= client-version since))))

;; ---------------------------------------------------------------------------
;; Tree substitution
;;
;; Walks a Hiccup tree bottom-up. At each component vector, if the
;; component isn't supported by `caps`, replace the entire subtree
;; with [:wun/WebFrame {:missing <kw>}]. Otherwise recurse into the
;; children. Strings, numbers, and non-keyword-headed vectors pass
;; through unchanged.

(defn- has-props? [v]
  (and (vector? v) (>= (count v) 2) (map? (second v))))

(declare substitute)

(defn- substitute-children [v caps]
  (let [hp?      (has-props? v)
        props    (when hp? (second v))
        children (if hp? (drop 2 v) (rest v))
        tag      (first v)]
    (into (cond-> [tag] hp? (conj props))
          (map #(substitute % caps) children))))

(defn substitute
  "Return a tree with any unsupported component subtrees replaced by
   [:wun/WebFrame {:missing <kw>}]. Idempotent on already-substituted
   trees because :wun/WebFrame is required to be in every client's
   caps list."
  ([tree] (substitute tree {}))
  ([tree caps]
   (cond
     (not (vector? tree)) tree

     (not (keyword? (first tree))) (mapv #(substitute % caps) tree)

     (not (supported? caps (first tree)))
     [:wun/WebFrame {:missing (first tree)}]

     :else (substitute-children tree caps))))
