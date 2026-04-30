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
;;   new <app-name>                copy templates/component-pack/ into ../<app>/
;;   help [cmd]                    short usage
;;
;; Resolves the wun monorepo root by walking up from $PWD looking for
;; the marker file `wun-server/deps.edn`. All file writes are idempotent
;; -- re-running a generator is safe; if a file already exists the
;; generator skips it and prints a hint.

(ns wun.cli
  (:require [babashka.fs       :as fs]
            [babashka.process  :as p]
            [clojure.string    :as str]))

;; ---------------------------------------------------------------------------
;; ANSI

(def ^:private ansi
  {:reset  "[0m"
   :dim    "[2m"
   :bold   "[1m"
   :red    "[31m"
   :green  "[32m"
   :yellow "[33m"
   :blue   "[34m"
   :cyan   "[36m"})

(defn- c [color & xs]
  (str (ansi color) (apply str xs) (ansi :reset)))

(defn- step [& xs]
  (println (c :cyan "›") (apply str xs)))

(defn- ok [& xs]
  (println (c :green "✓") (apply str xs)))

(defn- warn [& xs]
  (println (c :yellow "!") (apply str xs)))

(defn- err [& xs]
  (binding [*out* *err*]
    (println (c :red "✗") (apply str xs))))

;; ---------------------------------------------------------------------------
;; Repo discovery

(def ^:private marker "wun-server/deps.edn")

(defn- walk-up-for-marker [start]
  (loop [dir (fs/canonicalize start)]
    (cond
      (fs/exists? (fs/path dir marker)) (str dir)
      (= (str dir) "/")                 nil
      :else                             (recur (fs/parent dir)))))

(defn- find-repo-root []
  ;; First try $PWD (user invoked inside the monorepo); then fall
  ;; back to the script's own location, which lets `wun new` work
  ;; from any directory.
  (or (walk-up-for-marker ".")
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

(defn- dev-mode?
  "True when the user is actively developing the wun framework itself
   (vs. consuming it). We skip auto-upgrade prompts in dev mode --
   they'd be noisy and would trip on uncommitted work."
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
        (warn "missing " d)))))

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

(defn- cmd-new-app [[name & _]]
  (when (or (not name) (str/blank? name))
    (err "usage: wun new app <app-name>") (System/exit 2))
  (let [dst (copy-template! "app" name)]
    (println)
    (println "  next steps:")
    (println "    cd" name)
    (println "    npm install                  # shadow-cljs needs node deps")
    (println "    wun dev                      # server + cljs watch together")
    (println "    open http://localhost:8081")
    (println)
    (println "  the template assumes wun is a sibling clone -- see the")
    (println "  README in" (str dst) "to switch to remote refs.")))

(defn- cmd-new-pack [[name & _]]
  (when (or (not name) (str/blank? name))
    (err "usage: wun new pack <pack-name>") (System/exit 2))
  (let [dst (copy-template! "component-pack" name)]
    (println)
    (println "  next steps: cd" name "&& open README.md to fill in the renderers")
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
    (println "               :git/tag \"" tag "\"  :deps/root \"wun-server\" (or wun-shared / wun-web)")
    (println "    Swift    : .package(url: \"https://github.com/Holy-Coders/wun.git\",")
    (println "                        from: \"" (str/replace tag #"^v" "") "\")")
    (println "    Android  : implementation(\"com.github.Holy-Coders.wun:wun-android:" tag "\")")
    (println "               // requires JitPack on the resolver list")))

;; ---------------------------------------------------------------------------
;; help

(def ^:private usage
  (str/join
   "\n"
   ["Wun developer CLI"
    ""
    "  wun doctor                       check dev environment"
    "  wun dev                          server + shadow-cljs watch (Ctrl-C to stop both)"
    "  wun run <server|web|ios|android> run a single target"
    "  wun add component <ns>/<Name>    multi-platform component scaffold"
    "  wun add screen    <ns>/<name>    new screen .cljc"
    "  wun add intent    <ns>/<name>    new intent .cljc"
    "  wun new app  <name>              standalone app scaffold (server + web + ios + android)"
    "  wun new pack <name>              user-component pack scaffold"
    "  wun release  <version>           tag + push (e.g. v0.1.0)"
    "  wun upgrade                      pull latest wun + surface BREAKING changes"
    "  wun migrations <list|apply ...>  list / apply codemod scripts in migrations/"
    "  wun help                         this message"
    ""
    (str "  Set " (c :dim "WUN_NO_AUTO_UPGRADE=1") " to suppress the auto-check.")]))

(defn- cmd-help [_args] (println usage))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private skip-auto-check
  ;; Commands where prompting would be wrong (the user is already
  ;; addressing the upgrade or just asking for help).
  #{"upgrade" "help" "doctor" "release" "migrations" nil})

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
      (when (and root (not (dev-mode? root)))
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
    "dev"         (cmd-dev        args)
    "run"         (cmd-run        args)
    "new"         (cmd-new        args)
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
