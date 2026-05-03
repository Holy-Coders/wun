#!/usr/bin/env bb
;; Wun developer CLI. One source file, runs under babashka.
;;
;; Subcommands:
;;   doctor                        verify env (jvm, clojure, swift, gradle)
;;   dev                           start server + shadow-cljs watch concurrently
;;   run <target>                  one of: server | web | ios | android
;;   add component <ns>/<Name>     scaffold a multi-platform component
;;   add screen    <ns>/<name>     scaffold a screen .cljc
;;   add intent    <ns>/<name>     scaffold an intent .cljc
;;   new <app-name> [--link]       copy templates/app/ into ../<app>/
;;   link                          register the cwd checkout (or link an app to it)
;;   unlink                        unregister checkout (or restore an app's deps.edn)
;;   help [cmd]                    short usage
;;
;; Resolves the wun monorepo root by walking up from $PWD looking for
;; the marker file `wun-server/deps.edn`. All file writes are idempotent
;; -- re-running a generator is safe; if a file already exists the
;; generator skips it and prints a hint.

(ns wun.cli
  (:require [babashka.fs       :as fs]
            [babashka.process  :as p]
            [clojure.pprint    :as pprint]
            [clojure.string    :as str]))

;; ---------------------------------------------------------------------------
;; ANSI

(def ^:private ansi
  {:reset     "[0m"
   :dim       "[2m"
   :bold      "[1m"
   :italic    "[3m"
   :red       "[31m"
   :green     "[32m"
   :yellow    "[33m"
   :blue      "[34m"
   :magenta   "[35m"
   :cyan      "[36m"
   :grey      "[90m"
   :brred     "[91m"
   :brgreen   "[92m"
   :bryellow  "[93m"
   :brblue    "[94m"
   :brmagenta "[95m"
   :brcyan    "[96m"})

(defn- c [color & xs]
  (str (or (ansi color) "") (apply str xs) (ansi :reset)))

;; Glyphs picked for clarity at a glance:
;;   ▸ start  ✓ ok  ! warn  ✗ err  · info  ─ section rule  ◌ inert/empty

(defn- step [& xs]
  (println (str (c :brcyan "▸") "  " (apply str xs))))

(defn- ok [& xs]
  (println (str (c :brgreen "✓") "  " (apply str xs))))

(defn- warn [& xs]
  (println (str (c :bryellow "!") "  " (apply str xs))))

(defn- err [& xs]
  (binding [*out* *err*]
    (println (str (c :brred "✗") "  " (apply str xs)))))

(defn- info [& xs]
  (println (str (c :grey "·") "  " (apply str xs))))

(defn- rule
  "Print a labelled section divider:  ── label ──────────────…
   Width clamped to 64 chars."
  ([label] (rule label 64))
  ([label width]
   (let [head   (str "── " label " ")
         filler (max 4 (- width (count head)))]
     (println (c :grey (str head (apply str (repeat filler "─"))))))))

;; ---------------------------------------------------------------------------
;; Repo discovery

(def ^:private marker "wun-server/deps.edn")

(defn- walk-up
  "Walk parent dirs from `start` until `pred` returns truthy on a dir.
   Returns the canonical string path or nil at root."
  [start pred]
  (loop [dir (fs/canonicalize start)]
    (cond
      (pred dir)        (str dir)
      (= (str dir) "/") nil
      :else             (recur (fs/parent dir)))))

(defn- walk-up-for-marker [start]
  (walk-up start #(fs/exists? (fs/path % marker))))

;; ---------------------------------------------------------------------------
;; Active editable Wun (~/.config/wun/active.edn)
;;
;; A "linked" wun checkout is the one a user currently develops against.
;; `wun link` from inside a checkout writes its absolute path here; the
;; CLI, the MCP server, and the templates all consult this file so an
;; agent / app uses the same source tree the user is iterating on.
;; install.sh writes this on a fresh install, so a default install is
;; implicitly linked.
;;
;; Resolution order (most specific wins):
;;   1. WUN_HOME env var
;;   2. ~/.config/wun/active.edn :root
;;   3. nil  (callers fall back to script-relative discovery)

(def ^:private config-dir
  (str (fs/path (or (System/getenv "XDG_CONFIG_HOME")
                    (str (System/getenv "HOME") "/.config"))
                "wun")))

(def ^:private active-file
  (str (fs/path config-dir "active.edn")))

(defn- read-active []
  (when (fs/exists? active-file)
    (try (read-string (slurp active-file))
         (catch Throwable _ nil))))

(defn- write-active! [m]
  (fs/create-dirs (fs/parent active-file))
  (spit active-file (with-out-str (pprint/pprint m))))

(defn- delete-active! []
  (when (fs/exists? active-file) (fs/delete active-file)))

(defn- active-wun-root
  "Return the absolute path of the active editable Wun, or nil if none.
   Validates that the path still contains the wun-server marker -- if a
   user nuked their checkout, we return nil instead of a stale entry."
  []
  (let [candidate (or (System/getenv "WUN_HOME")
                      (:root (read-active)))]
    (when (and candidate (fs/exists? (fs/path candidate marker)))
      (str (fs/canonicalize candidate)))))

(defn- inside-wun-checkout? [dir]
  (fs/exists? (fs/path dir marker)))

(defn- looks-like-wun-app?
  "True when `dir` has a deps.edn that mentions a wun/wun-* dep AND the
   dir itself isn't a wun checkout (so we don't misclassify wun-server/)."
  [dir]
  (let [deps (fs/path dir "deps.edn")]
    (and (fs/exists? deps)
         (not (inside-wun-checkout? dir))
         (boolean
          (re-find #"wun/wun-(shared|server|web)"
                   (try (slurp (str deps)) (catch Throwable _ "")))))))

(defn- find-app-root [start]
  (walk-up start looks-like-wun-app?))

(defn- find-repo-root []
  ;; Search order:
  ;;   1. cwd's monorepo (user invoked inside wun)
  ;;   2. the active linked checkout (user invoked inside an app
  ;;      that's been `wun link`-ed -- so add/status/etc. operate
  ;;      against the editable wun, not a local cache)
  ;;   3. the script's own checkout (script-relative bootstrap)
  (or (walk-up-for-marker ".")
      (active-wun-root)
      (when *file* (walk-up-for-marker (fs/parent *file*)))))

(defn- repo-root []
  (or (find-repo-root)
      (do (err "not inside a wun monorepo (no " marker " up the tree)")
          (System/exit 2))))

;; ---------------------------------------------------------------------------
;; Upgrade helpers
;;
;; Cache lives at ~/.cache/wun/upgrade-state.edn so it survives
;; reinstalls and isn't checked into the wun working tree. Format:
;;
;;   {:checked-at  1748000000   ;; epoch seconds of the last fetch
;;    :ahead       3            ;; commits between local HEAD and origin/master
;;    :breaking    1            ;; how many of those have BREAKING in the subject
;;    :local-sha   "abc123..."
;;    :upstream    "def456..."
;;    :declined    1748000010}  ;; user said no this session; suppress until next fetch

(def ^:private cache-file
  (str (fs/path (or (System/getenv "XDG_CACHE_HOME")
                    (str (System/getenv "HOME") "/.cache"))
                "wun" "upgrade-state.edn")))

(defn- read-cache []
  (when (fs/exists? cache-file)
    (try (read-string (slurp cache-file))
         (catch Throwable _ nil))))

(defn- write-cache! [m]
  (fs/create-dirs (fs/parent cache-file))
  (spit cache-file (pr-str m)))

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- git [root & args]
  (apply p/sh
         (-> ["git" "-C" root]
             (into args))
         {:out :string :err :string :continue true}))

(defn- shell-out [{:keys [exit out]}]
  (when (zero? exit) (str/trim (or out ""))))

(defn- on-master? [root]
  (= "master" (shell-out (git root "rev-parse" "--abbrev-ref" "HEAD"))))

(defn- working-tree-dirty? [root]
  (some? (shell-out (git root "status" "--porcelain"))))

(defn- ahead-of-upstream? [root]
  (let [n (some-> (shell-out (git root "rev-list" "--count" "origin/master..HEAD"))
                  (Integer/parseInt))]
    (and n (pos? n))))

(defn- framework-dev-checkout?
  "True when the user is actively developing the wun framework itself
   (vs. consuming it): working tree dirty, on a non-master branch, or
   ahead of upstream. We skip auto-upgrade prompts here -- they'd be
   noisy and would trip on uncommitted work. Note: orthogonal to the
   `wun link` editable-install workflow; both can be true at once."
  [root]
  (or (working-tree-dirty? root)
      (not (on-master? root))
      (ahead-of-upstream? root)))

(def ^:private fetch-stale-secs (* 24 60 60))   ; 24h
(def ^:private decline-stale-secs 3600)         ; 1h "remind me later"

(defn- refresh-upgrade-state!
  "Fetch upstream and write the cache. Returns the new state."
  [root]
  (git root "fetch" "--quiet" "origin" "master")
  (let [behind (some-> (shell-out (git root "rev-list" "--count" "HEAD..origin/master"))
                       (Integer/parseInt))
        breaking (when (and behind (pos? behind))
                   (count
                    (->> (shell-out (git root "log" "--format=%s"
                                         "HEAD..origin/master"))
                         (#(or % ""))
                         str/split-lines
                         (filter #(re-find #"\bBREAKING\b" %)))))
        local    (shell-out (git root "rev-parse" "HEAD"))
        upstream (shell-out (git root "rev-parse" "origin/master"))
        state    {:checked-at (now-secs)
                  :ahead      (or behind 0)
                  :breaking   (or breaking 0)
                  :local-sha  local
                  :upstream   upstream}]
    (write-cache! state)
    state))

(defn- get-upgrade-state
  "Return cached state, refreshing iff older than fetch-stale-secs.
   Returns nil if we couldn't fetch (offline, no remote, etc.)."
  [root]
  (let [cached (read-cache)
        fresh? (and cached
                    (< (- (now-secs) (:checked-at cached 0))
                       fetch-stale-secs))]
    (if fresh?
      cached
      (try (refresh-upgrade-state! root)
           (catch Throwable _ cached)))))

(defn- tty? []
  (some? (System/console)))

(defn- prompt!
  "Read a y/n answer from the user, defaulting to `default`."
  [question default]
  (print (str question " "
              (if default "[Y/n]" "[y/N]") " "))
  (flush)
  (let [line (some-> (read-line) str/trim str/lower-case)]
    (cond
      (str/blank? line) default
      (#{"y" "yes"} line) true
      :else false)))

;; ---------------------------------------------------------------------------
;; doctor

(defn- which [bin]
  (-> (p/sh ["which" bin] {:out :string :err :string :continue true})
      :out str/trim not-empty))

(defn- first-line [s]
  (->> (str/split (or s "") #"\n")
       (map str/trim)
       (remove str/blank?)
       ;; Skip purely decorative lines (e.g. gradle's row of dashes).
       (remove #(re-matches #"-+|=+" %))
       first
       (#(or % ""))))

(defn- check [label bin args]
  (if-let [path (which bin)]
    (let [{:keys [out err]} (p/sh (cons bin args) {:out :string :err :string :continue true})
          version (first-line (or (not-empty out) err ""))]
      (ok (format "%-8s %s  %s" label path (c :dim version))))
    (warn (format "%-8s missing  (install via: %s)" label
                  (case label
                    "java"     "https://adoptium.net or `brew install --cask temurin`"
                    "clojure"  "brew install clojure/tools/clojure"
                    "swift"    "Xcode + xcode-select --install"
                    "gradle"   "brew install gradle"
                    "node"     "brew install node"
                    "bb"       "brew install borkdude/brew/babashka"
                    "")))))

(defn- canonical-or-nil
  "Resolve `p` to its canonical absolute path, or return nil if the path
   doesn't exist. (babashka.fs/canonicalize on some platforms returns
   the lexical path for non-existent files; we explicitly guard that.)"
  [p]
  (when (fs/exists? p)
    (try (str (fs/canonicalize p)) (catch Throwable _ nil))))

(defn- app-deps-wun-paths
  "Parse an app's deps.edn and return a seq of [sym local-root-path]
   for each wun/wun-* :local/root entry. Regex-based -- handles deps
   under :aliases just as well as top-level."
  [deps-edn-path]
  (let [text (try (slurp (str deps-edn-path)) (catch Throwable _ ""))
        ;; Match `wun/wun-X {... :local/root "..." ...}` on a single
        ;; line so we don't pick up multi-line examples in `;;`
        ;; comment blocks. (See wun-dep-re for the same constraint.)
        re   #"(wun/wun-(?:shared|server|web))[ \t]+\{[^{}\n\r]*:local/root\s+\"([^\"]+)\"[^{}\n\r]*\}"]
    (for [[_ sym path] (re-seq re text)] [sym path])))

(defn- cmd-doctor [_args]
  (step "checking dev environment")
  (check "java"    "java"    ["-version"])
  (check "clojure" "clojure" ["-Sdescribe"])
  (check "node"    "node"    ["--version"])
  (check "swift"   "swift"   ["--version"])
  (check "gradle"  "gradle"  ["--version"])
  (check "bb"      "bb"      ["--version"])
  (println)
  (step "verifying wun checkout")
  (let [root (repo-root)]
    (ok "repo root: " root)
    (doseq [d ["wun-server" "wun-shared" "wun-web" "wun-ios" "wun-android"]]
      (if (fs/exists? (fs/path root d))
        (ok "found " d)
        (warn "missing " d))))
  (println)
  (step "editable install (wun link)")
  (let [active (active-wun-root)
        from   (cond
                 (System/getenv "WUN_HOME")  "WUN_HOME"
                 (:root (read-active))       (str active-file)
                 :else                       nil)]
    (cond
      active   (ok "active editable Wun: " (c :bold active) "  " (c :dim (str "(" from ")")))
      :else    (info "no editable Wun registered  "
                     (c :dim "(run `wun link` from inside a wun checkout)"))))
  (when-let [app-dir (find-app-root ".")]
    (info "current app: " app-dir)
    (let [active (active-wun-root)
          entries (app-deps-wun-paths (fs/path app-dir "deps.edn"))]
      (if (empty? entries)
        (info "no wun/wun-* :local/root entries in deps.edn  "
              (c :dim "(may be using :git/url -- not linked)"))
        (doseq [[sym path] entries
                :let [absolute (canonical-or-nil
                                (if (str/starts-with? path "/")
                                  path
                                  (str (fs/path app-dir path))))]]
          (cond
            (nil? absolute)
            (warn sym " -> " path "  " (c :red "(path does not exist)"))
            (and active (str/starts-with? absolute active))
            (ok sym " -> " absolute)
            active
            (warn sym " -> " absolute "  "
                  (c :red "drift: active is ") (c :bold active))
            :else
            (info sym " -> " absolute "  " (c :dim "(no active link to compare)"))))))))

;; ---------------------------------------------------------------------------
;; status -- per-component coverage matrix across server / web / iOS / Android
;;
;; Reads the source tree directly rather than introspecting registries
;; at runtime. That way the matrix works even when the server isn't
;; up, and reflects truth-at-rest (what's actually shipped) rather
;; than runtime registration order.

(defn- find-files [root subpath ext]
  (let [base (fs/path root subpath)]
    (when (fs/exists? base)
      (filter #(str/ends-with? (str %) ext) (fs/glob base "**")))))

(defn- read-all
  "Return the concatenated text of every file matching `ext` under `subpath`."
  [root subpath ext]
  (->> (find-files root subpath ext)
       (map #(slurp (str %)))
       (str/join "\n")))

(defn- declared-components [root]
  ;; All `(defcomponent :ns/Name ...)` declarations in shared cljc.
  (let [text (str (read-all root "wun-shared/src" ".cljc"))
        re   #"\(defcomponent\s+:([A-Za-z][\w.-]*/[A-Za-z][\w.-]*)"]
    (->> (re-seq re text)
         (map second)
         distinct
         sort)))

(defn- component-coverage [root component-keyword]
  ;; Component-keyword is "ns/Name". Return a map of platform ->
  ;; "implemented" / "fallback (web)" / "missing".
  (let [needle    (str "\"" component-keyword "\"")
        kw-needle (str ":" component-keyword)
        ;; Web: registered via wun.web.foundation or user namespace
        web-text (read-all root "wun-web/src"     ".cljs")
        ios-text (str (read-all root "wun-ios/Sources"         ".swift")
                      (read-all root "wun-ios-example/Sources" ".swift"))
        and-text (str (read-all root "wun-android/src"         ".kt")
                      (read-all root "wun-android-example/src" ".kt"))
        srv-text (read-all root "wun-server/src/wun/server" ".clj")]
    {:web     (cond
                (re-find (re-pattern (str "register!\\s+" kw-needle)) web-text) :impl
                (str/includes? web-text needle) :impl
                :else :fallback)
     :ios     (if (str/includes? ios-text needle) :impl :fallback)
     :android (if (str/includes? and-text needle) :impl :fallback)
     :server  (cond
                (re-find (re-pattern (str "defmethod\\s+render-component\\s+" kw-needle))
                         srv-text)
                :impl
                ;; Anything declared in shared/components is server-known
                ;; even without a custom HTML mapping (it falls through
                ;; to the default <div class=wun-unknown>).
                :else :default)}))

(defn- coverage-glyph [v]
  (case v
    :impl     (c :brgreen "✓")
    :fallback (c :bryellow "◌")
    :default  (c :grey "·")
    (c :brred "✗")))

(defn- visible-len [s]
  ;; ANSI escape sequences are zero-width on a terminal but real chars
  ;; in the string -- strip them when computing column widths.
  (count (str/replace (or s "") #"\x1b\[[0-9;]*m" "")))

(defn- pad [s n]
  (let [s    (or s "")
        vlen (visible-len s)]
    (if (>= vlen n) s (str s (apply str (repeat (- n vlen) " "))))))

(defn- cmd-status [_args]
  (let [root  (repo-root)
        comps (declared-components root)]
    (when (empty? comps)
      (err "no defcomponent declarations found under wun-shared/src/")
      (System/exit 0))
    (rule "components")
    (println)
    (println (str (pad "  component" 26)
                  (pad "web" 6)
                  (pad "ios" 6)
                  (pad "android" 10)
                  "server-html"))
    (println (str (pad "  ─────────" 26)
                  (pad "───" 6)
                  (pad "───" 6)
                  (pad "───────" 10)
                  "───────────"))
    (doseq [k comps]
      (let [{:keys [web ios android server]} (component-coverage root k)]
        (println (str "  "
                      (pad (str ":" k) 24)
                      (pad (coverage-glyph web)     6)
                      (pad (coverage-glyph ios)     6)
                      (pad (coverage-glyph android) 10)
                      (coverage-glyph server)))))
    (println)
    (info (str (c :brgreen "✓") " native renderer present  "
               (c :bryellow "◌") " WebFrame fallback  "
               (c :grey "·") " server default HTML"))
    (println)
    (let [stats (frequencies (map (fn [k] (-> (component-coverage root k) :ios)) comps))]
      (info (str "iOS coverage:     " (or (:impl stats) 0) "/" (count comps)
                 " (" (or (:fallback stats) 0) " WebFrame fallback)")))
    (let [stats (frequencies (map (fn [k] (-> (component-coverage root k) :android)) comps))]
      (info (str "Android coverage: " (or (:impl stats) 0) "/" (count comps)
                 " (" (or (:fallback stats) 0) " WebFrame fallback)")))))

;; ---------------------------------------------------------------------------
;; run / dev

(defn- port-in-use? [^long port]
  ;; Try to bind a server socket; if BindException the port's taken.
  (try
    (let [s (java.net.ServerSocket. port)]
      (.close s)
      false)
    (catch java.net.BindException _ true)
    (catch Throwable _ false)))

(defn- listener-pid [^long port]
  ;; Best-effort identification of the process holding `port`. lsof
  ;; isn't available everywhere, but on macOS / Linux it usually is.
  ;; `-tiTCP:<port>` (one argument, no space) prints just the PID.
  (let [{:keys [exit out]}
        (p/sh ["lsof" (str "-tiTCP:" port) "-sTCP:LISTEN"]
              {:out :string :err :string :continue true})]
    (when (zero? exit)
      (some-> out str/trim str/split-lines first not-empty))))

(defn- bail-if-in-use! [^long port label]
  (when (port-in-use? port)
    (err "port " port " is already in use (needed for " label ")")
    (when-let [pid (listener-pid port)]
      (println (str "  PID " (c :bold pid) " is listening; kill it with:"))
      (println (str "    kill " pid)))
    (println (str "  or set things up so " label " runs on a free port."))
    (System/exit 2)))

(defn- run-server [root]
  (step "starting wun-server on :8080")
  (-> (p/process {:dir (str (fs/path root "wun-server"))
                  :inherit true}
                 "clojure" "-M" "-m" "wun.server.core")))

(defn- ensure-node-modules! [^String web-dir]
  ;; shadow-cljs needs node_modules/react etc.; the installer only
  ;; clones, so the first dev run on a fresh checkout has to npm
  ;; install first. Idempotent -- npm short-circuits if everything
  ;; is already there.
  (when-not (fs/exists? (fs/path web-dir "node_modules"))
    (step "installing npm deps in " web-dir " (one-time)")
    (let [{:keys [exit]} (p/sh {:dir web-dir :inherit true} "npm" "install")]
      (when-not (zero? exit)
        (err "npm install failed in " web-dir)
        (System/exit (int exit))))))

(defn- run-shadow [root]
  (let [web-dir (str (fs/path root "wun-web"))]
    (ensure-node-modules! web-dir)
    (step "starting shadow-cljs watch (browser http://localhost:8081)")
    (-> (p/process {:dir web-dir :inherit true}
                   "npx" "shadow-cljs" "watch" "app"))))

(defn- exit-code-of [proc]
  ;; @proc waits and yields a process record; :exit holds the code.
  (try (:exit @proc) (catch Throwable _ 1)))

(defn- cmd-run [[target & _]]
  (let [root (repo-root)]
    (case target
      "server"   (do (bail-if-in-use! 8080 "wun-server")
                     (System/exit (exit-code-of (run-server root))))
      "web"      (do (bail-if-in-use! 8081 "shadow-cljs http")
                     (System/exit (exit-code-of (run-shadow root))))
      "ios"      (do (step "running iOS demo (swift run wun-demo-mac)")
                     (System/exit
                      (exit-code-of
                       (p/process {:dir (str (fs/path root "wun-ios-example"))
                                   :inherit true}
                                  "swift" "run" "wun-demo-mac"))))
      "android"  (do (step "running Compose Desktop demo")
                     (System/exit
                      (exit-code-of
                       (p/process {:dir (str (fs/path root "wun-android-example"))
                                   :inherit true}
                                  "gradle" "run"))))
      (do (err "unknown run target: " target)
          (println "  expected one of: server | web | ios | android")
          (System/exit 2)))))

(defn- cmd-dev [_args]
  (let [root (repo-root)]
    (bail-if-in-use! 8080 "wun-server")
    (bail-if-in-use! 8081 "shadow-cljs http")
    (step "starting server + shadow-cljs together (Ctrl-C to stop both)")
    (let [server (run-server root)
          shadow (run-shadow root)]
      (try
        ;; Whichever child exits first decides we're done.
        (let [code (exit-code-of server)]
          (if (zero? code)
            (warn "wun-server exited cleanly")
            (err  "wun-server exited with code " code)))
        (finally
          (p/destroy-tree shadow)
          (p/destroy-tree server))))))

;; ---------------------------------------------------------------------------
;; link / unlink
;;
;; `wun link` is mode-sensitive on cwd:
;;
;;   inside a wun checkout  ->  register THIS checkout as active.
;;                              Re-symlink bin/wun onto PATH and write
;;                              ~/.config/wun/active.edn.
;;
;;   inside a wun-app       ->  rewrite the app's deps.edn so each
;;                              wun/wun-* :local/root points at the
;;                              active checkout's absolute path. Save
;;                              a one-shot .deps.edn.linkbak so unlink
;;                              can restore the original (typically a
;;                              :git/url + :git/tag form).
;;
;; `wun unlink` is the reverse. In an app: restore from the backup; if
;; no backup, rewrite to a :git/url form using the active checkout's
;; latest tag. In a wun checkout: drop active.edn (the symlink is left
;; alone -- removing it would surprise users who only wanted to
;; switch checkouts).

(defn- writable-dir? [d]
  (try (and (fs/exists? d)
            (.canWrite (java.io.File. (str d))))
       (catch Throwable _ false)))

(defn- choose-bin-target
  "Mirror install.sh:choose_target. Prefer /usr/local/bin if writable;
   else ensure ~/.local/bin exists and use it. Returns the target path
   string or nil if neither is available."
  []
  (cond
    (writable-dir? "/usr/local/bin")
    "/usr/local/bin/wun"

    :else
    (let [home    (System/getenv "HOME")
          home-bin (str (fs/path home ".local" "bin"))]
      (when home
        (fs/create-dirs home-bin)
        (str (fs/path home-bin "wun"))))))

(defn- on-path? [bin-dir]
  (let [path (or (System/getenv "PATH") "")]
    (some #(= % bin-dir) (str/split path #":"))))

(defn- install-symlink! [wun-root]
  (if-let [target (choose-bin-target)]
    (let [src (str (fs/path wun-root "bin" "wun"))]
      (when (or (fs/sym-link? target) (fs/exists? target))
        (fs/delete target))
      (fs/create-sym-link target src)
      (let [bin-dir (str (fs/parent target))]
        (ok "symlinked " target " -> " src)
        (when-not (on-path? bin-dir)
          (warn bin-dir " is not on PATH; add it with:")
          (println (str "    export PATH=\"" bin-dir ":$PATH\"")))))
    (do (err "no writable bin dir found (tried /usr/local/bin and ~/.local/bin)")
        (System/exit 2))))

(def ^:private wun-sub-dirs
  ;; Maps the deps coord symbol to its sub-project dir under the wun root.
  {"wun/wun-shared" "wun-shared"
   "wun/wun-server" "wun-server"
   "wun/wun-web"    "wun-web"})

;; Matches `wun/wun-shared { ... }` -- single-level, single-line map
;; literal, no nested braces. The single-line constraint keeps us from
;; matching multi-line examples in `;;` comment blocks. Standard
;; `:local/root`/`:git/url` shapes fit. A multi-line dep map (rare in
;; practice) is left alone and will surface as drift in `wun doctor`.
(def ^:private wun-dep-re
  #"(wun/wun-(?:shared|server|web))[ \t]+\{[^{}\n\r]*\}")

(defn- relink-deps-text
  "Rewrite each wun/wun-* dep map in `text` to point at `active-root`
   via :local/root. Returns the new text."
  [text active-root]
  (str/replace text wun-dep-re
    (fn [[_ sym]]
      (str sym " {:local/root \"" active-root "/" (wun-sub-dirs sym) "\"}"))))

(defn- unlink-deps-text
  "Inverse: rewrite each wun/wun-* dep map to a :git/url form pinned to
   `tag`. We don't have the sha at hand; users can fill it in or use
   `wun release` workflow."
  [text tag]
  (str/replace text wun-dep-re
    (fn [[_ sym]]
      (str sym " {:git/url \"https://github.com/Holy-Coders/wun.git\""
           " :git/tag \"" tag "\""
           " :deps/root \"" (wun-sub-dirs sym) "\"}"))))

(defn- snippet-ios [active-root]
  (str "    .package(path: \"" active-root "/wun-ios\"),"))

(defn- snippet-android [active-root]
  (str "    includeBuild(\"" active-root "/wun-android\")"))

(defn- print-platform-hints [active-root]
  (info (c :dim "iOS: in ios/Package.swift, set the wun .package(...) to:"))
  (println (str "      " (snippet-ios active-root)))
  (info (c :dim "Android: in android/settings.gradle.kts, set:"))
  (println (str "      " (snippet-android active-root))))

(defn- cmd-link-register
  "Register `wun-root` as the active editable Wun and re-symlink bin/wun."
  [wun-root]
  (let [prev (:root (read-active))]
    (write-active! {:root        wun-root
                    :linked-at   (now-secs)
                    :linked-from (str (fs/canonicalize "."))
                    :version     1})
    (cond
      (nil? prev)         (ok "registered active editable Wun: " (c :bold wun-root))
      (= prev wun-root)   (info "active editable Wun unchanged: " wun-root)
      :else               (do (ok "switched active editable Wun")
                              (info "  was: " prev)
                              (info "  now: " (c :bold wun-root)))))
  (install-symlink! wun-root)
  (println)
  (info "to dogfood from an app: " (c :bold "cd <app> && wun link")))

(defn- cmd-link-app
  "Rewrite the app's deps.edn to point at the active editable Wun."
  [app-dir]
  (let [active (active-wun-root)]
    (when-not active
      (err "no active editable Wun -- run `wun link` from inside a wun checkout first")
      (System/exit 2))
    (let [deps-path (str (fs/path app-dir "deps.edn"))
          backup    (str (fs/path app-dir ".deps.edn.linkbak"))
          before    (slurp deps-path)
          after     (relink-deps-text before active)]
      (cond
        (= before after)
        (info "deps.edn already points at " active "  (no-op)")

        :else
        (do (when-not (fs/exists? backup)
              (spit backup before)
              (info "saved backup: .deps.edn.linkbak"))
            (spit deps-path after)
            (ok "rewrote deps.edn -> " (c :bold active))))
      (println)
      (print-platform-hints active))))

(defn- cmd-link [_args]
  (let [cwd      (str (fs/canonicalize "."))
        checkout (walk-up-for-marker cwd)
        app      (when-not checkout (find-app-root cwd))]
    (cond
      checkout (cmd-link-register checkout)
      app      (cmd-link-app app)
      :else    (do (err "run `wun link` inside a wun checkout (to register it)")
                   (println "  or inside a wun-app dir (to link its deps.edn at the active checkout).")
                   (System/exit 2)))))

(defn- latest-tag [root]
  (or (shell-out (git root "describe" "--tags" "--abbrev=0"))
      "v0.1.0"))

(defn- cmd-unlink-app [app-dir]
  (let [deps-path (str (fs/path app-dir "deps.edn"))
        backup    (str (fs/path app-dir ".deps.edn.linkbak"))]
    (cond
      (fs/exists? backup)
      (do (spit deps-path (slurp backup))
          (fs/delete backup)
          (ok "restored deps.edn from .deps.edn.linkbak"))

      :else
      (let [active (active-wun-root)
            tag    (or (some-> active latest-tag) "v0.1.0")
            text   (slurp deps-path)
            new    (unlink-deps-text text tag)]
        (if (= text new)
          (info "no wun/wun-* deps to unlink in " deps-path)
          (do (spit deps-path new)
              (ok "rewrote deps.edn -> :git/url tag " (c :bold tag))
              (info "review the result; you may want to add :git/sha as well")))))))

(defn- cmd-unlink [_args]
  (let [cwd      (str (fs/canonicalize "."))
        checkout (walk-up-for-marker cwd)
        app      (when-not checkout (find-app-root cwd))]
    (cond
      app      (cmd-unlink-app app)
      checkout (do (delete-active!)
                   (ok "removed active.edn  "
                       (c :dim "(symlink left intact; reinstall to remove it)")))
      :else    (do (err "run `wun unlink` inside a wun-app dir or wun checkout")
                   (System/exit 2)))))

;; ---------------------------------------------------------------------------
;; Generators

(defn- parse-ns-name
  "Accepts `ns/Name` (e.g. `myapp/Card`) and returns
   {:ns \"myapp\" :name \"Card\" :keyword \":myapp/Card\"
    :pascal \"MyappCard\" :class-pascal \"MyAppCard\"}"
  [s]
  (when-not (and s (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*/[a-zA-Z][a-zA-Z0-9_-]*" s))
    (err "expected NS/NAME (e.g. myapp/Card), got: " (pr-str s))
    (System/exit 2))
  (let [[ns- name-] (str/split s #"/")
        cap         (fn [w] (str (str/upper-case (subs w 0 1)) (subs w 1)))
        words       (fn [w] (str/split w #"[_-]"))
        pascal      (str/join "" (map cap (words ns-)))
        name-pascal (str/join "" (map cap (words name-)))]
    {:ns           ns-
     :name         name-
     :keyword      (str ":" ns- "/" name-)
     :pascal       (str pascal name-pascal)
     :class-pascal name-pascal}))

(defn- write-once! [path content]
  (let [p (fs/path path)]
    (if (fs/exists? p)
      (warn "skip (already exists): " (str p))
      (do (fs/create-dirs (fs/parent p))
          (spit (str p) content)
          (ok "wrote " (str p))))))

(defn- append-if-key-missing!
  "Append `insertion` to the file if `key-marker` isn't already in it.
   Used for files we extend at end-of-file (e.g. shared cljc)."
  [path key-marker insertion]
  (let [p (str (fs/path path))]
    (if-not (fs/exists? p)
      (warn "skip (file missing): " p)
      (let [content (slurp p)]
        (if (str/includes? content key-marker)
          (warn "skip (already contains " key-marker "): " p)
          (do (spit p (str content insertion))
              (ok "patched " p)))))))

(defn- splice-after-marker!
  "Insert `insertion` immediately after the first occurrence of
   `splice-marker` -- but only if `key-marker` isn't already in the
   file. Used for registry files we extend in the middle (e.g. an
   iOS WunExample.swift with a register block)."
  [path splice-marker key-marker insertion]
  (let [p (str (fs/path path))]
    (if-not (fs/exists? p)
      (warn "skip (file missing): " p)
      (let [content (slurp p)]
        (cond
          (str/includes? content key-marker)
          (warn "skip (already contains " key-marker "): " p)

          (not (str/includes? content splice-marker))
          (warn "skip (no splice marker '" splice-marker "'): " p)

          :else
          (do (spit p (str/replace-first content splice-marker
                                         (str splice-marker insertion)))
              (ok "patched " p)))))))

(defn- cmd-add-component [[ident & _]]
  (let [{:keys [ns name keyword pascal class-pascal]} (parse-ns-name ident)
        root (repo-root)]
    (step "adding component " (c :bold keyword))

    ;; 1. Shared cljc declaration. Create file if absent; otherwise
    ;;    append a new `defcomponent` iff the keyword isn't already in it.
    (let [path        (str (fs/path root "wun-shared/src" ns "components.cljc"))
          insertion   (format
                       "\n(defcomponent %s\n  {:since    1\n   :schema   [:map]\n   :fallback :web\n   :ios      \"%s\"\n   :android  \"%s\"})\n"
                       keyword class-pascal class-pascal)]
      (if (fs/exists? path)
        (append-if-key-missing! path keyword insertion)
        (write-once!
         path
         (format
          "(ns %s.components\n  (:require [wun.components :refer [defcomponent]]))\n%s"
          ns insertion))))

    ;; 2. iOS renderer + registration splice. Class name follows the
    ;;    existing example pack convention (just the component name,
    ;;    no namespace prefix); the file is `<Name>Renderer.swift`.
    (write-once!
     (str (fs/path root "wun-ios-example/Sources/WunExample"
                   (str class-pascal "Renderer.swift")))
     (format "// Auto-generated by `wun add component %s`. SwiftUI renderer.\nimport SwiftUI\nimport Wun\n\npublic enum %s {\n    public static let render: WunComponent = { props, children in\n        AnyView(SwiftUI.Text(WunChildren.flatten(children)))\n    }\n}\n"
             ident class-pascal))
    (splice-after-marker!
     (str (fs/path root "wun-ios-example/Sources/WunExample/WunExample.swift"))
     "// AUTO-REGISTER-MARK"
     (str "\"" ns "/" name "\"")
     (format "\n        registry.register(\"%s/%s\", %s.render)" ns name class-pascal))

    ;; 3. Android renderer + registration splice.
    (write-once!
     (str (fs/path root "wun-android-example/src/main/kotlin/myapp/example"
                   (str class-pascal "Renderer.kt")))
     (format "// Auto-generated by `wun add component %s`. Compose renderer.\npackage myapp.example\n\nimport androidx.compose.material.Text\nimport wun.WunComponent\nimport wun.foundation.Children\n\nobject %sRenderer {\n    val render: WunComponent = { _, children ->\n        Text(Children.flatten(children))\n    }\n}\n"
             ident class-pascal))
    (splice-after-marker!
     (str (fs/path root "wun-android-example/src/main/kotlin/myapp/example/WunExample.kt"))
     "// AUTO-REGISTER-MARK"
     (str "\"" ns "/" name "\"")
     (format "\n        registry.register(\"%s/%s\", %sRenderer.render)" ns name class-pascal))

    (println)
    (ok "next steps:")
    (println "  - flesh out the SwiftUI / Compose renderer bodies")
    (println (str "  - require '" ns ".components from wun-server's entry namespace"))
    (println "  - bump the :since key when you ship breaking schema changes")))

(defn- cmd-add-screen [[ident & _]]
  (let [{:keys [ns name keyword]} (parse-ns-name ident)
        root (repo-root)
        path (str (fs/path root "wun-shared/src" ns (str (str/replace name #"-" "_") ".cljc")))]
    (step "adding screen " (c :bold keyword))
    (write-once!
     path
     (format "(ns %s.%s\n  (:require [wun.screens :refer [defscreen]]))\n\n(defscreen %s\n  {:path   \"/%s\"\n   :render\n   (fn [state]\n     [:wun/Stack {:gap 12 :padding 24}\n      [:wun/Heading {:level 1} \"%s screen\"]\n      [:wun/Text    {:variant :body} \"TODO: render the screen here.\"]\n      [:wun/Button  {:on-press {:intent :wun/pop :params {}}} \"← Back\"]])})\n"
             ns name keyword name name))
    (println)
    (ok "next steps:")
    (println "  - require " (c :bold (str ns "." name)) " from wun-server's entry namespace")
    (println "  - require it from wun-web/src/wun/web/core.cljs too if web should render it")))

(defn- cmd-add-intent [[ident & _]]
  (let [{:keys [ns name keyword]} (parse-ns-name ident)
        root (repo-root)
        path (str (fs/path root "wun-shared/src" ns
                           (str (str/replace (str name "_intent") #"-" "_") ".cljc")))]
    (step "adding intent " (c :bold keyword))
    (write-once!
     path
     (format "(ns %s.%s-intent\n  (:require [wun.intents :refer [definent]]))\n\n(definent %s\n  {:params [:map]\n   :morph  (fn [state _params] state)})\n"
             ns name keyword))
    (println)
    (ok "next steps:")
    (println "  - implement the morph (state, params) -> state")
    (println "  - require " (c :bold (str ns "." name "-intent")) " from wun-server core")))

;; ---------------------------------------------------------------------------
;; new <kind> <name>
;;
;; Templates use the literal placeholder `myapp` in file contents and
;; directory names, and `MyApp` (PascalCase) wherever Swift/Kotlin
;; wants type-style names. After copying we rewrite both forms in
;; place so the user gets a project named after them, not myapp.
;;
;; Project names must be a valid Clojure ns / Kotlin package: lower
;; ASCII alpha + digits, no separators. Hyphenated names are out of
;; scope for now -- they require different forms for Clojure
;; (hyphen-keep) vs Kotlin packages (no separator), and the simple
;; substitution can't tell which is which.

(def ^:private name-pattern #"[a-z][a-z0-9]*")

(defn- valid-app-name? [s]
  (boolean (and s (re-matches name-pattern s))))

(defn- pascalize [s]
  (str (str/upper-case (subs s 0 1)) (subs s 1)))

(def ^:private text-extensions
  #{"clj" "cljc" "cljs" "edn" "swift" "kt" "kts"
    "html" "css" "js" "json" "md" "yml" "yaml"
    "sh" "bb" "txt" "gradle" "gitignore"
    ;; Added for Docker / DB / fly fragments:
    "sql" "env" "example" "properties" "conf" "toml" "lock"})

(defn- text-file? [^java.nio.file.Path p]
  (let [name (str (fs/file-name p))]
    (or (text-extensions (some-> (re-find #"\.([^.]+)$" name) second))
        (#{".gitignore" ".dockerignore" ".env" ".env.example"
           "Package.swift" "Dockerfile" "fly.toml"} name))))

(defn- substitute-content! [path slug pascal]
  (try
    (let [orig (slurp (str path))
          ;; Order matters: replace the PascalCase form first (it's
          ;; longer + more specific), then the lowercase form. Both
          ;; placeholders are case-sensitive so they don't collide.
          new  (-> orig
                   (str/replace #"MyApp" pascal)
                   (str/replace #"myapp" slug))]
      (when (not= orig new)
        (spit (str path) new)))
    (catch Throwable e
      (warn "skipped " (str path) ": " (.getMessage e)))))

(defn- rewrite-tree! [^String dst slug pascal]
  ;; 1. Substitute in every text file.
  (doseq [p (fs/glob dst "**")
          :when (and (fs/regular-file? p) (text-file? p))]
    (substitute-content! p slug pascal))
  ;; 2. Rename templated directories (depth-first so parent renames
  ;;    don't invalidate child paths). The template uses two literal
  ;;    dir names: `myapp` (Clojure ns dir / Kotlin pkg dir) and
  ;;    `MyAppDemo` (Swift target dir).
  (let [renames {"myapp"      slug
                 "MyAppDemo"  (str pascal "Demo")}
        dirs (->> (fs/glob dst "**")
                  (filter fs/directory?)
                  ;; Depth-descending so we rename children before parents.
                  (sort-by #(- (count (str %)))))]
    (doseq [d dirs
            :let [n (str (fs/file-name d))
                  new-name (renames n)]
            :when (and new-name (not= new-name n))]
      (let [target (fs/path (fs/parent d) new-name)]
        (when-not (fs/exists? target)
          (fs/move (str d) (str target)))))))

(defn- copy-template! [template-dir name]
  (let [root (repo-root)
        src  (fs/path root "templates" template-dir)
        dst  (fs/path (fs/canonicalize ".") name)]
    (when-not (fs/exists? src)
      (err "template missing: " (str src)) (System/exit 2))
    (when (fs/exists? dst)
      (err "destination already exists: " (str dst)) (System/exit 2))
    (step "scaffolding " (c :bold name) " from templates/" template-dir)
    (fs/copy-tree (str src) (str dst))
    (ok "created " (str dst))
    dst))

(defn- bail-if-bad-name! [name kind]
  (when (or (not name) (str/blank? name))
    (err "usage: wun new " kind " <name>") (System/exit 2))
  (when-not (valid-app-name? name)
    (err "invalid name " (pr-str name) " -- must match " (str name-pattern))
    (println "  (lowercase ASCII letter followed by letters and digits;")
    (println "   no hyphens, dots, or underscores in the name)")
    (System/exit 2)))

;; ---------------------------------------------------------------------------
;; new-app flag parsing
;;
;; Accepts (in any order):
;;   --link
;;   --docker
;;   --no-auth
;;   --db {none|sqlite|postgres|datomic}
;;
;; Anything else => exit 2 with usage. Defaults: db=none, docker=false,
;; auth=true (only meaningful when db != none).

(def ^:private db-values #{"none" "sqlite" "postgres" "datomic"})

(defn- new-app-usage []
  (str "usage: wun new app <name> "
       "[--db none|sqlite|postgres|datomic] [--docker] [--no-auth] [--link]"))

(defn- parse-new-app-args [args]
  (loop [acc {:positional [] :link? false :docker? false
              :auth? true   :db "none"}
         xs  args]
    (if (empty? xs)
      acc
      (let [a (first xs)]
        (cond
          (= a "--link")     (recur (assoc acc :link? true)    (rest xs))
          (= a "--docker")   (recur (assoc acc :docker? true)  (rest xs))
          (= a "--no-auth")  (recur (assoc acc :auth? false)   (rest xs))
          (= a "--db")       (let [v (second xs)]
                               (when-not (and v (db-values v))
                                 (err "--db expects one of "
                                      (str/join "|" (sort db-values)))
                                 (System/exit 2))
                               (recur (assoc acc :db v) (drop 2 xs)))
          (str/starts-with? a "--db=")
          (let [v (subs a 5)]
            (when-not (db-values v)
              (err "--db expects one of " (str/join "|" (sort db-values)))
              (System/exit 2))
            (recur (assoc acc :db v) (rest xs)))
          (str/starts-with? a "--")
          (do (err "unknown flag: " a)
              (println "  " (new-app-usage))
              (System/exit 2))
          :else
          (recur (update acc :positional conj a) (rest xs)))))))

;; ---------------------------------------------------------------------------
;; Fragment overlays
;;
;; A fragment is a directory of files mirroring the layout they should
;; land at in the destination app. `overlay-fragment!` walks every file
;; under `src` and:
;;   - Files NOT ending in .append are copied verbatim to the matching
;;     sub-path in `dst` (creating parent dirs as needed). If the file
;;     already exists at the destination we leave it alone -- overlays
;;     never silently clobber base-template content.
;;   - Files ending in .append are appended into the same-named
;;     (sans-suffix) destination file, wrapped in a sentinel block so
;;     subsequent runs don't double-append. If the destination file
;;     doesn't exist yet, we treat it as a copy.

(defn- sentinel-open  [id] (str ";; >>> WUN-FRAGMENT:" id " >>>\n"))
(defn- sentinel-close [id] (str ";; <<< WUN-FRAGMENT:" id " <<<\n"))

(defn- already-applied? [^String existing id]
  (str/includes? existing (str "WUN-FRAGMENT:" id)))

(defn- append-with-sentinel! [dst-file ^String body id]
  (let [existing (if (fs/exists? dst-file) (slurp (str dst-file)) "")
        sep      (if (or (str/blank? existing)
                         (str/ends-with? existing "\n"))
                   ""
                   "\n")]
    (when-not (already-applied? existing id)
      (spit (str dst-file)
            (str existing sep
                 (sentinel-open id)
                 (cond-> body
                   (not (str/ends-with? body "\n")) (str "\n"))
                 (sentinel-close id))))))

(defn- overlay-fragment! [^String src ^String dst id]
  (let [src-path (fs/path src)]
    (when-not (fs/exists? src-path)
      (err "fragment missing: " src) (System/exit 2))
    (step "overlay " (c :bold id) " from " src)
    ;; `:hidden true` picks up .dockerignore, .env.example, .github/.
    ;; `:follow-links true` keeps things sane if a fragment ever uses
    ;; a symlink; today none does.
    (doseq [p (fs/glob src-path "**" {:hidden true :follow-links true})
            :when (fs/regular-file? p)]
      (let [rel        (str (fs/relativize src-path p))
            append?    (str/ends-with? rel ".append")
            overwrite? (str/ends-with? rel ".overwrite")
            dst-rel    (cond
                         append?    (subs rel 0 (- (count rel) 7))
                         overwrite? (subs rel 0 (- (count rel) 10))
                         :else      rel)
            dst-file   (fs/path dst dst-rel)]
        (fs/create-dirs (fs/parent dst-file))
        (cond
          append?
          (append-with-sentinel! dst-file (slurp (str p)) id)

          overwrite?
          (fs/copy (str p) (str dst-file)
                   {:replace-existing true})

          (fs/exists? dst-file)
          (info "skip (exists): " dst-rel)

          :else
          (fs/copy (str p) (str dst-file)))))))

;; ---------------------------------------------------------------------------
;; Surgical patches into base-template files

;; Shared by every --db != none scaffold (config.clj reads env, log.clj
;; writes JSON-formatted log lines).
(def ^:private common-deps
  '{org.clojure/data.json {:mvn/version "2.5.1"}})

(def ^:private db-deps
  {:sqlite   '{org.xerial/sqlite-jdbc                  {:mvn/version "3.46.1.0"}
               com.github.seancorfield/next.jdbc       {:mvn/version "1.3.939"}
               com.zaxxer/HikariCP                     {:mvn/version "5.1.0"}}
   :postgres '{org.postgresql/postgresql               {:mvn/version "42.7.4"}
               com.github.seancorfield/next.jdbc       {:mvn/version "1.3.939"}
               com.zaxxer/HikariCP                     {:mvn/version "5.1.0"}}
   :datomic  '{com.datomic/local                       {:mvn/version "1.0.277"}}})

(def ^:private auth-deps
  '{buddy/buddy-hashers {:mvn/version "2.0.167"}})

(def ^:private build-alias
  '{:build {:deps       {io.github.clojure/tools.build
                         {:git/tag "v0.10.5" :git/sha "2a21b7a"}}
            :ns-default build}})

(defn- patch-deps-edn! [deps-path {:keys [db docker? auth?]}]
  (let [text (slurp (str deps-path))
        db?  (and db (not= db :none))]
    (cond
      ;; No-op when there's nothing to splice -- preserves the
      ;; --db none + no-docker scaffold byte-identical to today.
      (and (not db?) (not docker?))
      :no-op

      (str/includes? text "WUN-PATCHED")
      (info "deps.edn already patched -- skipping")

      :else
      (let [parsed   (try (clojure.edn/read-string text)
                          (catch Throwable e
                            (err "could not parse " deps-path ": " (.getMessage e))
                            (System/exit 2)))
            with-db  (cond-> parsed
                       db?            (update :deps merge common-deps)
                       db?            (update :deps merge (db-deps db))
                       (and db? auth?) (update :deps merge auth-deps))
            with-bld (cond-> with-db
                       docker?
                       (update :aliases merge build-alias))
            out      (with-out-str
                       (println ";; deps.edn -- WUN-PATCHED by `wun new app`.")
                       (println ";;")
                       (println ";; Wun framework deps default to sibling :local/root entries.")
                       (println ";; To pin to a release instead, swap any :local/root for")
                       (println ";; {:git/url \"https://github.com/Holy-Coders/wun.git\"")
                       (println ";;  :git/tag \"v0.1.0\" :git/sha \"<full-sha>\"")
                       (println ";;  :deps/root \"wun-shared\"}.")
                       (println)
                       (pprint/pprint with-bld))]
        (spit (str deps-path) out)))))

(defn- patch-server-main! [main-path slug {:keys [db auth?]}]
  (when (fs/exists? main-path)
    (let [text   (slurp (str main-path))
          db?    (and db (not= db :none))
          dbns   (str slug ".server.db")
          notesn (str slug ".notes")
          notes-store (str slug ".server.notes-store")
          authns (str slug ".server.auth")
          authui (str slug ".auth")
          ;; Step 1: extend (:require ...) with new namespaces. We add
          ;; both the server-only `*.server.db`/`*.server.notes-store`
          ;; namespaces (need to be loaded server-side) and the
          ;; cross-platform `*.notes`/`*.auth` namespaces (whose
          ;; side-effecting `definent`/`defscreen` registrations
          ;; populate the registries).
          require-additions
          (str/join "\n            "
                    (concat (when db? [dbns notes-store notesn
                                       (str slug ".persist")])
                            (when (= db :postgres) [(str slug ".server.bus")])
                            (when (and db? auth?) [authns authui])
                            ;; aliased require must be a vector form
                            ["[wun.server.state :as wun-state]"]))
          text   (if (and db? (not (str/includes? text dbns)))
                   (str/replace-first
                    text
                    "wun.foundation.components"
                    (str "wun.foundation.components\n            "
                         require-additions))
                   text)
          ;; Step 2: insert (db/init!), the per-conn init-state-fn that
          ;; preloads :notes (and hydrates from the persist layer when
          ;; a slice resumes), and (auth/init!) before (http/start! ...).
          ;; The init-state-fn fires once per fresh conn slice; the
          ;; add-note morph re-folds :notes from DB on every subsequent
          ;; write so the slice stays in sync with the row table.
          init-block
          (str (when db?
                 (str "(" dbns "/init!)\n  "
                      "(myapp.persist/init!)\n  "
                      "(wun-state/register-init-state-fn!\n   "
                      "(fn [_conn-id ctx]\n     "
                      "(let [token   (:session-token ctx)\n           "
                      (if auth?
                        (str "session (when token (myapp.server.auth/load-session-by-token token))\n           ")
                        "session nil\n           ")
                      "saved   (when session (myapp.persist/load-state-by-user (:user-id session)))]\n       "
                      "(cond-> {:notes (" notes-store "/list-notes)}\n         "
                      "saved   (merge saved)\n         "
                      "session (assoc :session session)))))\n  "))
               (when (and db? auth?)
                 (str "(" authns "/init!)\n  "))
               (when (= db :postgres)
                 (str "(myapp.server.bus/start!)\n  ")))
          text   (if (and db?
                          (not (str/includes? text (str "(" dbns "/init!)"))))
                   (str/replace-first
                    text
                    "(http/start!"
                    (str init-block "(http/start!"))
                   text)]
      (spit (str main-path) text))))

(defn- patch-readme! [readme-path {:keys [db docker? auth?]}]
  (when (fs/exists? readme-path)
    (let [text   (slurp (str readme-path))
          chunks (cond-> []
                   docker?
                   (conj (str "## Deploy with Docker\n\n"
                              "```bash\n"
                              "docker compose up --build\n"
                              "open http://localhost:8080\n"
                              "```\n\n"
                              "The app reads `PORT`, `HOST`, `LOG_LEVEL`, and `SESSION_SECRET`\n"
                              "from the environment (see `.env.example`). The `Dockerfile` is\n"
                              "multi-stage: stage 1 builds an uberjar via `clj -T:build uber`,\n"
                              "stage 2 runs it on `eclipse-temurin:21-jre`.\n"))
                   (and (not= db "none") (not= db :none))
                   (conj (str "## Database (" (name db) ")\n\n"
                              (case db
                                ("sqlite" :sqlite)
                                (str "SQLite is embedded; the DB file lives at `./data/myapp.db`\n"
                                     "(or `/app/data/myapp.db` in Docker). Migrations under\n"
                                     "`resources/migrations/*.sql` run on startup.\n")
                                ("postgres" :postgres)
                                (str "Postgres is brought up by `docker-compose.yml` when you\n"
                                     "use `--docker`. Otherwise set `DATABASE_URL` to your own\n"
                                     "Postgres URL, e.g. `jdbc:postgresql://localhost:5432/myapp`.\n"
                                     "Migrations under `resources/migrations/*.sql` run on startup.\n")
                                ("datomic" :datomic)
                                (str "Datomic Local runs in-process; data persists at `./data/datomic`\n"
                                     "(or `/app/data/datomic` in Docker). The schema lives in\n"
                                     "`resources/datomic/schema.edn` and is transacted on startup.\n"))
                              "\nThe home screen at `/` is a hub: counter demo,\n"
                              "links to the DB-backed `/notes` feature, the auth-gated\n"
                              "`/dashboard`, and a top nav showing your login state.\n"))
                   (and auth? (not= db "none") (not= db :none))
                   (conj (str "## Auth\n\n"
                              "Cookie-less, token-based session auth is wired in. The\n"
                              "session token is held in `(:session state)` and persists\n"
                              "across reloads via the existing client-side hot-cache.\n\n"
                              "- `/signup`: create an account (bcrypt-hashed password).\n"
                              "- `/login`:  sign in to an existing account.\n"
                              "- `/dashboard`: an auth-gated screen demonstrating the\n"
                              "  render-time `(:session state)` check pattern.\n"
                              "- `/notes`: the compose form is hidden when logged out.\n\n"
                              "Auth is **single-tenant** in this scaffold: state lives in\n"
                              "one global atom, so 'logged in as X' is shared across\n"
                              "connections. Per-connection sessions land when the\n"
                              "framework grows per-conn state. For multi-user production,\n"
                              "you'll want to thread the session token into intent params\n"
                              "and look up the user in each morph (rather than reading\n"
                              "from `(:session state)`).\n\n"
                              "Set `SESSION_SECRET` (32+ random bytes) in production --\n"
                              "the dev default is unsafe.\n"))
                   docker?
                   (conj (str "## Deploy to fly.io\n\n"
                              "```bash\n"
                              "fly launch --no-deploy   # accept the generated fly.toml\n"
                              "fly secrets set SESSION_SECRET=$(openssl rand -hex 32)\n"
                              (when (= (str db) "postgres")
                                "fly pg create && fly pg attach <pg-app-name>\n")
                              "fly deploy\n"
                              "```\n")))]
      (when (seq chunks)
        (let [marker "<!-- WUN-DEPLOY-DOCS -->"]
          (when-not (str/includes? text marker)
            (spit (str readme-path)
                  (str text "\n" marker "\n\n"
                       (str/join "\n" chunks)))))))))

(defn- cmd-new-app [args]
  (let [{:keys [positional link? docker? auth? db]} (parse-new-app-args args)
        name (first positional)]
    (bail-if-bad-name! name "app")
    (let [dst       (copy-template! "app" name)
          dst-s     (str dst)
          pascal    (pascalize name)
          root      (repo-root)
          db-key    (when (not= db "none") (keyword db))
          frag      (fn [id] (str (fs/path root "templates" "fragments" id)))
          has-frag? (fn [id] (fs/exists? (fs/path root "templates" "fragments" id)))]
      ;; Step 1: overlay fragments BEFORE rewrite-tree! so substitutions
      ;; (myapp -> slug) also apply to overlay content.
      (when db-key
        (when (has-frag? "_common")
          (overlay-fragment! (frag "_common") dst-s "common"))
        (when (has-frag? (str "db/" db))
          (overlay-fragment! (frag (str "db/" db)) dst-s (str "db-" db)))
        (when (and auth? (has-frag? "auth"))
          (overlay-fragment! (frag "auth") dst-s "auth"))
        (when (and auth? (has-frag? (str "auth-db/" db)))
          (overlay-fragment! (frag (str "auth-db/" db)) dst-s
                             (str "auth-db-" db))))
      (when docker?
        (when (has-frag? "docker")
          (overlay-fragment! (frag "docker") dst-s "docker"))
        (when (has-frag? "ci")
          (overlay-fragment! (frag "ci") dst-s "ci"))
        (when (has-frag? "fly")
          (overlay-fragment! (frag "fly") dst-s "fly"))
        (when (and db-key (has-frag? (str "docker-db/" db)))
          (overlay-fragment! (frag (str "docker-db/" db)) dst-s
                             (str "docker-db-" db))))
      ;; Step 2: surgical patches into base files.
      (patch-deps-edn!     (str (fs/path dst "deps.edn"))
                           {:db (or db-key :none) :docker? docker? :auth? auth?})
      (when db-key
        (patch-server-main! (str (fs/path dst "src" "myapp" "server" "main.clj"))
                            "myapp" {:db db-key :auth? auth?}))
      (patch-readme!       (str (fs/path dst "README.md"))
                           {:db db :docker? docker? :auth? auth?})
      ;; Step 3: existing substitution sweep over the merged tree.
      (rewrite-tree! dst-s name pascal)
      (when link?
        (println)
        (if (active-wun-root)
          (cmd-link-app dst-s)
          (warn "--link requested but no active editable Wun is registered;"
                " run `wun link` inside a wun checkout first.")))
      (println)
      (println "  next steps:")
      (println (str "    cd " name))
      (println "    npm install                  # shadow-cljs needs node deps")
      (when (and db-key (= db "postgres") (not docker?))
        (println "    export DATABASE_URL=jdbc:postgresql://localhost:5432/" name
                 "?user=" name "&password=" name)
        (println "      # ^ point at your own Postgres; --docker would compose one"))
      (println "    wun dev                      # server + cljs watch together")
      (println "    open http://localhost:8081")
      (when docker?
        (println "    docker compose up --build    # production-style image")
        (println "    open http://localhost:8080"))
      (when db-key
        (println "    open http://localhost:8081/notes")
        (when auth?
          (println "    open http://localhost:8081/signup")))
      (println)
      (cond
        link?
        (println (str "  deps.edn linked to " (active-wun-root) "."))

        (active-wun-root)
        (do (println "  to dogfood the active wun checkout, run:")
            (println (str "    cd " name " && wun link")))

        :else
        (do (println "  the template assumes wun is a sibling clone -- see the")
            (println (str "  README in " dst-s " to switch to remote refs.")))))))

(defn- cmd-new-pack [[name & _]]
  (bail-if-bad-name! name "pack")
  (let [dst    (copy-template! "component-pack" name)
        pascal (pascalize name)]
    (rewrite-tree! (str dst) name pascal)
    (println)
    (println (str "  next steps: cd " name " && open README.md to fill in the renderers"))
    dst))

(defn- cmd-new [args]
  (case (first args)
    "app"   (cmd-new-app  (rest args))
    "pack"  (cmd-new-pack (rest args))
    ;; Bare `wun new <name>` defaults to `app` (the more common ask).
    nil     (do (err "usage: wun new <app|pack> <name>") (System/exit 2))
    (cmd-new-app args)))

;; ---------------------------------------------------------------------------
;; upgrade

(defn- show-upgrade-summary [root state]
  (let [{:keys [ahead breaking local-sha upstream]} state]
    (println (str (c :bold "wun") " is " (c :bold ahead)
                  " commit" (when (not= 1 ahead) "s") " behind master"
                  (when (and breaking (pos? breaking))
                    (str ", " (c :red (str breaking " BREAKING"))))))
    (println (str "  " (subs (or local-sha "?") 0 (min 7 (count (or local-sha "?"))))
                  " -> "
                  (subs (or upstream "?") 0 (min 7 (count (or upstream "?"))))))
    (when (and ahead (pos? ahead))
      (let [log (shell-out (git root "log" "--format=  %h %s"
                                "HEAD..origin/master"))]
        (when log
          (println)
          (println (c :dim "incoming commits:"))
          (doseq [line (str/split-lines log)]
            (if (re-find #"\bBREAKING\b" line)
              (println (c :red line))
              (println line))))))))

(defn- list-new-migrations [root state]
  ;; Show migrations/* files added between local-sha and upstream so
  ;; the user knows whether they need to run anything after the pull.
  (when-let [{:keys [local-sha upstream]} state]
    (let [diff (shell-out (git root "diff" "--name-only"
                               (str local-sha ".." upstream) "--" "migrations/"))]
      (when (and diff (not (str/blank? diff)))
        (println)
        (println (c :dim "new migrations to consider after upgrade:"))
        (doseq [path (str/split-lines diff)]
          (println (str "  " path)))
        (println)
        (println "  apply with: " (c :bold "wun migrations apply <id> --dir <your-app>"))))))

(defn- cmd-upgrade [_args]
  (let [root  (repo-root)]
    (when (working-tree-dirty? root)
      (err "wun checkout has local changes -- commit or stash before upgrading")
      (println "  (the framework checkout is at: " root ")")
      (System/exit 2))
    (when-not (on-master? root)
      (warn "current branch is not master; staying on it for the fetch"))
    (step "fetching origin/master")
    (let [state (refresh-upgrade-state! root)]
      (cond
        (zero? (:ahead state 0))
        (do (ok "already up to date") (System/exit 0))

        :else
        (do
          (show-upgrade-summary root state)
          (list-new-migrations root state)
          (println)
          (when (and (tty?)
                     (not (prompt! "Apply this upgrade?" false)))
            (write-cache! (assoc state :declined (now-secs)))
            (println "  declined; run " (c :bold "wun upgrade") " again to retry.")
            (System/exit 0))
          (step "fast-forwarding to origin/master")
          (let [{:keys [exit out err]} (git root "merge" "--ff-only" "origin/master")]
            (when-not (zero? exit)
              (binding [*out* *err*] (println (str/trim (or err out))))
              (System/exit (int exit))))
          (when (fs/exists? (fs/path root "wun-web/package.json"))
            (ensure-node-modules! (str (fs/path root "wun-web"))))
          (write-cache! (assoc state :ahead 0 :breaking 0
                               :local-sha (:upstream state)
                               :checked-at (now-secs)))
          (println)
          (ok "upgraded to " (subs (or (:upstream state) "?") 0 7))
          (when (pos? (:breaking state 0))
            (warn "this upgrade includes BREAKING changes -- review the commits above")
            (println "  if migrations/ files were added, apply them now."))
          (println "  run " (c :bold "wun doctor") " to re-verify your environment."))))))

;; ---------------------------------------------------------------------------
;; migrations

(defn- migrations-dir [root]
  (fs/path root "migrations"))

(defn- list-migrations [root]
  (let [dir (migrations-dir root)]
    (when (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter #(or (str/ends-with? (str %) ".bb")
                        (str/ends-with? (str %) ".clj")))
           (map #(str (fs/file-name %)))
           sort))))

(defn- migration-id [filename]
  ;; Convention: NNNN-slug.bb -- the leading digits are the id.
  (let [[id _] (re-find #"^(\d+)" filename)]
    id))

(defn- cmd-migrations-list [_args]
  (let [root (repo-root)
        files (list-migrations root)]
    (if (empty? files)
      (println "no migrations under " (str (migrations-dir root)))
      (do (println "available migrations:")
          (doseq [f files] (println "  " f))))))

(defn- cmd-migrations-apply [args]
  (let [{:keys [id dir]}
        (loop [m {} args (vec args)]
          (cond
            (empty? args) m
            (= "--dir" (first args)) (recur (assoc m :dir (second args)) (drop 2 args))
            :else (recur (assoc m :id (first args)) (rest args))))]
    (when-not id
      (err "usage: wun migrations apply <id> [--dir <path>]")
      (System/exit 2))
    (let [root (repo-root)
          target (or dir (str (fs/canonicalize ".")))
          file (->> (list-migrations root)
                    (filter #(or (= id %) (= id (migration-id %))))
                    first)]
      (when-not file
        (err "no migration matching " id) (System/exit 2))
      (step "applying " file " against " target)
      (let [{:keys [exit]}
            (p/sh ["bb" (str (fs/path (migrations-dir root) file)) target]
                  {:inherit true})]
        (System/exit (int exit))))))

(defn- cmd-migrations [args]
  (case (first args)
    "list"   (cmd-migrations-list (rest args))
    "apply"  (cmd-migrations-apply (rest args))
    (do (err "usage: wun migrations <list|apply>")
        (System/exit 2))))

;; ---------------------------------------------------------------------------
;; release

(defn- run-or-die [& args]
  (let [{:keys [exit out err]} (apply p/sh (vec args))]
    (when-not (zero? exit)
      (println (str/trim (or out "")))
      (binding [*out* *err*] (println (str/trim (or err ""))))
      (System/exit (int exit)))
    (str/trim (or out ""))))

(defn- cmd-release [[version & _]]
  (when-not (and version (re-matches #"v?\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?" version))
    (err "usage: wun release <version>   (e.g. v0.1.0 or 0.1.0)")
    (System/exit 2))
  (let [tag    (if (str/starts-with? version "v") version (str "v" version))
        root   (repo-root)
        dirty? (-> (p/sh ["git" "-C" root "status" "--porcelain"]
                         {:out :string :err :string})
                   :out str/trim seq)
        branch (run-or-die "git" "-C" root "rev-parse" "--abbrev-ref" "HEAD")]
    (when dirty?
      (err "working tree is dirty -- commit or stash first")
      (System/exit 2))
    (when-not (= "master" branch)
      (warn "current branch is " (c :bold branch)
            " (expected master); proceeding anyway"))
    (let [exists? (-> (p/sh ["git" "-C" root "rev-parse" "--verify" "--quiet" tag]
                            {:out :string :err :string :continue true})
                      :exit zero?)]
      (when exists?
        (err "tag " tag " already exists locally")
        (System/exit 2)))

    (step "tagging " (c :bold tag))
    (run-or-die "git" "-C" root "tag" "-a" tag "-m" (str "Release " tag))
    (step "pushing " tag " to origin")
    (run-or-die "git" "-C" root "push" "origin" branch "--tags")
    (println)
    (ok "released " tag)
    (println "  consumers can now pin to:")
    (println "    Clojure  : :git/url \"https://github.com/Holy-Coders/wun.git\"")
    (println (str "               :git/tag \"" tag "\"  :deps/root \"wun-server\" (or wun-shared / wun-web)"))
    (println "    Swift    : .package(url: \"https://github.com/Holy-Coders/wun.git\",")
    (println (str "                        from: \"" (str/replace tag #"^v" "") "\")"))
    (println (str "    Android  : implementation(\"com.github.Holy-Coders.wun:wun-android:" tag "\")"))
    (println "               // requires JitPack on the resolver list")))

;; ---------------------------------------------------------------------------
;; help

(def ^:private usage
  (str/join
   "\n"
   ["Wun developer CLI"
    ""
    "  wun doctor                       check dev environment"
    "  wun status                       per-component coverage across web/ios/android"
    "  wun dev                          server + shadow-cljs watch (Ctrl-C to stop both)"
    "  wun run <server|web|ios|android> run a single target"
    "  wun add component <ns>/<Name>    multi-platform component scaffold"
    "  wun add screen    <ns>/<name>    new screen .cljc"
    "  wun add intent    <ns>/<name>    new intent .cljc"
    "  wun new app  <name> [opts...]    standalone app scaffold (server + web + ios + android)"
    "    opts:  --db {none|sqlite|postgres|datomic}  add a DB layer + demo CRUD feature"
    "           --docker                              ship Dockerfile + compose + fly.toml + CI"
    "           --no-auth                             skip the cookie-session auth scaffold"
    "           --link                                link to the active editable wun checkout"
    "  wun new pack <name>              user-component pack scaffold"
    "  wun link                         register cwd's wun checkout (or link this app to it)"
    "  wun unlink                       unregister checkout (or restore app's deps.edn)"
    "  wun release  <version>           tag + push (e.g. v0.1.0)"
    "  wun upgrade                      pull latest wun + surface BREAKING changes"
    "  wun migrations <list|apply ...>  list / apply codemod scripts in migrations/"
    "  wun help                         this message"
    ""
    (str "  Set " (c :dim "WUN_NO_AUTO_UPGRADE=1") " to suppress the auto-check.")
    (str "  Set " (c :dim "WUN_HOME=<path>") " to override the active editable Wun.")]))

(defn- cmd-help [_args] (println usage))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private skip-auto-check
  ;; Commands where prompting would be wrong (the user is already
  ;; addressing the upgrade or just asking for help).
  #{"upgrade" "help" "doctor" "status" "release" "migrations"
    "link" "unlink" nil})

(defn- maybe-auto-check!
  "Before dispatching, check whether the user should upgrade and (in
   a TTY, when not in dev mode) prompt to do so. Skipped when:
   - the command is itself meta (upgrade/help/doctor/release/migrations)
   - WUN_NO_AUTO_UPGRADE is set
   - the user declined within the last hour
   - the user is in dev mode (working tree dirty / non-master / ahead)
   - we couldn't fetch (offline) -- silent skip"
  [cmd]
  (when-not (or (skip-auto-check cmd)
                (System/getenv "WUN_NO_AUTO_UPGRADE"))
    (let [root (find-repo-root)]
      (when (and root (not (framework-dev-checkout? root)))
        (let [state (get-upgrade-state root)
              recently-declined?
              (and (:declined state)
                   (< (- (now-secs) (:declined state))
                      decline-stale-secs))]
          (when (and state
                     (pos? (:ahead state 0))
                     (not recently-declined?))
            (println (str (c :yellow "!") " wun is "
                          (c :bold (:ahead state)) " commit"
                          (when (not= 1 (:ahead state)) "s")
                          " behind master"
                          (when (pos? (:breaking state 0))
                            (str " (" (c :red (str (:breaking state) " BREAKING")) ")"))))
            (when (tty?)
              (if (prompt! "  upgrade now?" false)
                (cmd-upgrade nil)
                (do (write-cache! (assoc state :declined (now-secs)))
                    (println (str "  ok, will remind again later. "
                                  "Set WUN_NO_AUTO_UPGRADE=1 to silence."))
                    (println))))))))))

(defn -main [& [cmd & args]]
  (maybe-auto-check! cmd)
  (case cmd
    "doctor"      (cmd-doctor     args)
    "status"      (cmd-status     args)
    "dev"         (cmd-dev        args)
    "run"         (cmd-run        args)
    "new"         (cmd-new        args)
    "link"        (cmd-link       args)
    "unlink"      (cmd-unlink     args)
    "release"     (cmd-release    args)
    "upgrade"     (cmd-upgrade    args)
    "migrations"  (cmd-migrations args)
    "help"        (cmd-help       args)
    nil           (cmd-help       args)
    "add"         (case (first args)
                    "component" (cmd-add-component (rest args))
                    "screen"    (cmd-add-screen    (rest args))
                    "intent"    (cmd-add-intent    (rest args))
                    (do (err "unknown 'add' subcommand: " (first args))
                        (println "  expected one of: component | screen | intent")
                        (System/exit 2)))
    (do (err "unknown command: " cmd)
        (println usage)
        (System/exit 2))))

(apply -main *command-line-args*)
