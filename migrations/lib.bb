#!/usr/bin/env bb
;; Migration helper library.
;;
;; Migrations come in two flavours:
;;
;;   1. `wun.migrations/regex-rewrite!` -- legacy. Quick to write,
;;      brittle on edge cases (it's the wrong tool for code with
;;      string literals containing the pattern, or for Hiccup that
;;      embeds Clojure-shaped strings). Phase-1 migrations used this.
;;
;;   2. `wun.migrations/ast-rewrite!` -- AST-based via rewrite-clj
;;      (bundled in babashka). Walks the source's parse tree, only
;;      rewrites symbol / list nodes that match a predicate, and
;;      preserves whitespace + comments verbatim. This is what new
;;      migrations use.
;;
;; Both helpers report:
;;   - Files visited
;;   - Files rewritten
;;   - Files where the migration believes a manual review is
;;     warranted (e.g. `conn-id` referenced somewhere it might not
;;     be in scope).
;;
;; Apps load this helper from their own migrations:
;;
;;   #!/usr/bin/env bb
;;   (load-file (str (System/getenv \"WUN_HOME\") \"/migrations/lib.bb\"))
;;   (require '[wun.migrations :as m])
;;
;;   (m/ast-rewrite!
;;     {:dir   (or (first *command-line-args*) \".\")
;;      :match (fn [zloc] ...)
;;      :replace (fn [zloc] ...)})

(ns wun.migrations
  (:require [babashka.fs    :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def default-globs ["**.clj" "**.cljc" "**.cljs" "**.bb" "**.edn"])

;; ---------------------------------------------------------------------------
;; Regex rewrites (legacy)

(defn regex-rewrite!
  "Apply `[pattern replacement]` rules across files matching `globs`
   under `dir`. Flexible but stupid -- prefer `ast-rewrite!` for
   anything more interesting than a one-liner. Returns a map
   `{:visited [...], :rewritten [...]}`."
  [{:keys [dir globs rules report-fn]
    :or   {globs default-globs report-fn (constantly nil)}}]
  (let [files     (mapcat #(fs/glob dir %) globs)
        rewritten (atom [])
        visited   (atom [])]
    (doseq [f     files
            :let  [path (str f)
                   orig (slurp path)
                   new  (reduce (fn [c [pat repl]]
                                  (str/replace c pat repl))
                                orig rules)]]
      (swap! visited conj path)
      (when (not= orig new)
        (spit path new)
        (swap! rewritten conj path)))
    (let [result {:visited (vec @visited) :rewritten (vec @rewritten)}]
      (report-fn result)
      result)))

;; ---------------------------------------------------------------------------
;; AST rewrites (preferred)

(defn- walk-zloc!
  "Walk every node of `zloc` depth-first. For each node where `match?`
   returns truthy, replace it with the result of `replace-fn`. Returns
   the rewritten zipper, plus a count of replacements made."
  [zloc match? replace-fn]
  (let [count* (atom 0)]
    (loop [z zloc]
      (cond
        (z/end? z)
        [(z/root z) @count*]

        (match? z)
        (do (swap! count* inc)
            (recur (z/next (replace-fn z))))

        :else
        (recur (z/next z))))))

(defn ast-rewrite!
  "Walk every code file under `dir` matching `globs`. For each node
   where `match?` zloc returns truthy, replace via `replace-fn`. Files
   with at least one replacement are written back; files without are
   left alone. Whitespace + comments are preserved verbatim because
   rewrite-clj round-trips them.

   `match?` and `replace-fn` are zloc -> ?. The zloc API is documented
   at https://github.com/clj-commons/rewrite-clj. Common matchers:

       (z/sexpr-able? z)
       (= 'foo (z/sexpr z))
       (and (z/list? z) (= '@ (z/sexpr (z/down z))))

   Returns `{:visited [...], :rewritten [...], :total-replacements n}`."
  [{:keys [dir globs match? replace-fn report-fn]
    :or   {globs default-globs report-fn (constantly nil)}}]
  (when-not (and match? replace-fn)
    (throw (ex-info "ast-rewrite! requires :match? and :replace-fn"
                    {:got [match? replace-fn]})))
  (let [files     (mapcat #(fs/glob dir %) globs)
        rewritten (atom [])
        visited   (atom [])
        total     (atom 0)]
    (doseq [f files
            :let [path (str f)
                  orig (slurp path)]]
      (swap! visited conj path)
      (try
        (let [zloc (z/of-string orig {:track-position? false})
              [root n] (walk-zloc! zloc match? replace-fn)]
          (when (pos? n)
            (let [out (z/root-string (z/of-node root))]
              (when (not= orig out)
                (spit path out)
                (swap! rewritten conj path)
                (swap! total + n)))))
        (catch Exception e
          (binding [*out* *err*]
            (println "  ! parse failed in" path "-" (.getMessage e))))))
    (let [result {:visited (vec @visited)
                  :rewritten (vec @rewritten)
                  :total-replacements @total}]
      (report-fn result)
      result)))

;; ---------------------------------------------------------------------------
;; Reporting

(defn print-report!
  "Default report formatter. Apps register this as `:report-fn` to
   get a uniform end-of-migration log."
  [migration-name {:keys [visited rewritten total-replacements]}]
  (println (format "[migration %s] visited=%d rewrote=%d replacements=%s"
                   migration-name
                   (count visited)
                   (count rewritten)
                   (or total-replacements "n/a")))
  (when (seq rewritten)
    (println "  files rewritten:")
    (doseq [f rewritten] (println "  -" f))))
