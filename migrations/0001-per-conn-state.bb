#!/usr/bin/env bb
;; Phase 1.E migration: per-connection state.
;;
;; Up to phase 1.D, every Wun server held a single global atom
;; `wun.server.state/app-state` and intent morphs swapped it. Phase
;; 1.E moves state into per-connection slices keyed by conn-id; the
;; global atom is gone. See docs/architecture/per-connection-state.md
;; for the why and the wire-level walkthrough.
;;
;; This codemod handles the mechanical rewrites: `app-state` reads
;; and writes get rewritten to `state-for` / `swap-state-for!` calls
;; assuming `conn-id` is in scope. Where it isn't (for example, a
;; render fn that takes only `state`), the migration FLAGS the call
;; site with a comment so a human can resolve it.
;;
;; Usage:
;;   wun migrations apply 0001-per-conn-state --dir <your-app-dir>
;;
;; Or:
;;   bb migrations/0001-per-conn-state.bb <target-dir>

(ns wun.migrations.per-conn-state
  (:require [babashka.fs   :as fs]
            [clojure.string :as str]))

(def ^:private target
  (or (first *command-line-args*)
      (do (println "usage: bb 0001-per-conn-state.bb <target-dir>")
          (System/exit 2))))

(def ^:private file-globs ["**.clj" "**.cljc" "**.cljs"])

(def ^:private rules
  "Each rule is [pattern replacement]. We deliberately don't insert
   inline TODO comments -- they break let-binding vectors and other
   tightly-bound forms. After the rewrite, the user gets a summary
   listing files where `conn-id` is referenced; they review whether
   each call site has it in scope."
  [;; Reads.
   [#"@wun\.server\.state/app-state"
    "(wun.server.state/state-for conn-id)"]
   [#"@state/app-state"
    "(state/state-for conn-id)"]

   ;; Writes.
   [#"\(swap!\s+state/app-state\s+"
    "(state/swap-state-for! conn-id "]
   [#"\(swap!\s+wun\.server\.state/app-state\s+"
    "(wun.server.state/swap-state-for! conn-id "]

   ;; Reset is rarer but possible. The trailing `(constantly` opens
   ;; a one-arg fn that the user closes manually around the value.
   [#"\(reset!\s+state/app-state\s+"
    "(state/swap-state-for! conn-id (constantly "]])

(defn- transform [content]
  (reduce (fn [c [pat repl]]
            (str/replace c pat repl))
          content
          rules))

(defn- run! []
  (let [files   (mapcat #(fs/glob target %) file-globs)
        changed (atom [])]
    (doseq [f files
            :let [orig (slurp (str f))
                  new  (transform orig)]
            :when (not= orig new)]
      (spit (str f) new)
      (swap! changed conj (str f)))
    (println (format "[migration 0001] rewrote %d file(s) under %s"
                     (count @changed) target))
    (when (seq @changed)
      (println)
      (println "  rewritten files (review each `conn-id` call site has it in scope):")
      (doseq [f @changed]
        (println "  -" f))
      (println)
      (println "  conn-id is the SSE-connection identifier the framework hands to")
      (println "  intent morphs and screen renders. If your old code read")
      (println "  `@state/app-state` from a fn that didn't already receive it,")
      (println "  thread it through (or refactor the fn to take state directly).")
      (println "  See docs/architecture/per-connection-state.md."))))

(run!)
