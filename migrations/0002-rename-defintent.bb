#!/usr/bin/env bb
;; Rename `definent` -> `defintent` across consumer apps.
;;
;; The macro that registers an intent is now spelled `defintent`,
;; matching the def-noun pattern used by `defcomponent` and
;; `defscreen`. The old `definent` spelling is gone from the
;; framework; this codemod rewrites consumer code to match.
;;
;; Idempotent: substring replacement only on the old token. After
;; running once there are no `definent` occurrences left, so a
;; second run is a no-op.
;;
;; Usage:
;;   wun migrations apply 0002-rename-defintent --dir <your-app-dir>
;;
;; Or:
;;   bb migrations/0002-rename-defintent.bb <target-dir>

(ns wun.migrations.rename-defintent
  (:require [babashka.fs    :as fs]
            [clojure.string :as str]))

(def ^:private target
  (or (first *command-line-args*)
      (do (println "usage: bb 0002-rename-defintent.bb <target-dir>")
          (System/exit 2))))

(def ^:private file-globs ["**.clj" "**.cljc" "**.cljs"])

(defn- transform [content]
  (str/replace content "definent" "defintent"))

(defn- run! []
  (let [files   (mapcat #(fs/glob target %) file-globs)
        changed (atom [])]
    (doseq [f files
            :let [orig (slurp (str f))
                  new  (transform orig)]
            :when (not= orig new)]
      (spit (str f) new)
      (swap! changed conj (str f)))
    (println (format "[migration 0002] rewrote %d file(s) under %s"
                     (count @changed) target))
    (when (seq @changed)
      (doseq [f @changed]
        (println "  -" f)))))

(run!)
