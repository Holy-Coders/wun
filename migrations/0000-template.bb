#!/usr/bin/env bb
;; Template migration. Copy this file to NNNN-some-slug.bb when
;; landing a real breaking change. Delete this template once a real
;; first migration ships.
;;
;; Usage:
;;   bb migrations/NNNN-some-slug.bb <target-dir>
;;
;; The CLI invokes you via `wun migrations apply NNNN [--dir <path>]`,
;; passing one positional arg: the directory to transform.

(ns wun.migrations.template
  (:require [babashka.fs   :as fs]
            [clojure.string :as str]))

(def ^:private target
  (or (first *command-line-args*)
      (do (println "usage: bb 0000-template.bb <target-dir>")
          (System/exit 2))))

;; --- example transformation ----------------------------------------------
;;
;; Walk every .clj / .cljc / .cljs / .swift / .kt file under target and
;; replace `:wun/Foo` with `:wun/Bar`. Adapt to whatever your migration
;; actually needs.

(def ^:private file-globs ["**.clj" "**.cljc" "**.cljs" "**.swift" "**.kt"])

(defn- transform [content]
  ;; (str/replace content ":wun/Foo" ":wun/Bar")
  content)

(defn- run! []
  (let [files (mapcat #(fs/glob target %) file-globs)
        changed (atom 0)]
    (doseq [f files
            :let [orig (slurp (str f))
                  new  (transform orig)]
            :when (not= orig new)]
      (spit (str f) new)
      (swap! changed inc))
    (println (format "[migration 0000] rewrote %d file(s) under %s"
                     @changed target))))

(run!)
