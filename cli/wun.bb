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

(defn- run-server [root]
  (step "starting wun-server on :8080")
  (-> (p/process {:dir (str (fs/path root "wun-server"))
                  :inherit true}
                 "clojure" "-M" "-m" "wun.server.core")))

(defn- run-shadow [root]
  (step "starting shadow-cljs watch (browser http://localhost:8081)")
  (-> (p/process {:dir (str (fs/path root "wun-web"))
                  :inherit true}
                 "npx" "shadow-cljs" "watch" "app")))

(defn- cmd-run [[target & _]]
  (let [root (repo-root)]
    (case target
      "server"   @(run-server root)
      "web"      @(run-shadow root)
      "ios"      (do (step "running iOS demo (swift run wun-demo-mac)")
                     @(p/process {:dir (str (fs/path root "wun-ios-example"))
                                  :inherit true}
                                 "swift" "run" "wun-demo-mac"))
      "android"  (do (step "running Compose Desktop demo")
                     @(p/process {:dir (str (fs/path root "wun-android-example"))
                                  :inherit true}
                                 "gradle" "run"))
      (do (err "unknown run target: " target)
          (println "  expected one of: server | web | ios | android")
          (System/exit 2)))))

(defn- cmd-dev [_args]
  (let [root (repo-root)]
    (step "starting server + shadow-cljs together (Ctrl-C to stop both)")
    (let [server (run-server root)
          shadow (run-shadow root)]
      ;; Wait on either; cancel the other on exit.
      (try
        (let [code (try @server (catch Throwable _ 1))]
          (warn "server exited with code " code))
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
;; new <app>

(defn- cmd-new [[name & _]]
  (when (or (not name) (str/blank? name))
    (err "usage: wun new <app-name>")
    (System/exit 2))
  (let [root (repo-root)
        src  (fs/path root "templates/component-pack")
        dst  (fs/path (fs/canonicalize ".") name)]
    (when (fs/exists? dst)
      (err "destination already exists: " (str dst))
      (System/exit 2))
    (step "scaffolding " (c :bold name) " from templates/component-pack")
    (fs/copy-tree (str src) (str dst))
    (ok "created " (str dst))
    (println "  next: cd" name "&& open the README to fill in the renderers")))

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
    "  wun new <app-name>               copy templates/component-pack/ next door"
    "  wun help                         this message"]))

(defn- cmd-help [_args] (println usage))

;; ---------------------------------------------------------------------------
;; Dispatch

(defn -main [& [cmd & args]]
  (case cmd
    "doctor"  (cmd-doctor args)
    "dev"     (cmd-dev    args)
    "run"     (cmd-run    args)
    "new"     (cmd-new    args)
    "help"    (cmd-help   args)
    nil       (cmd-help   args)
    "add"     (case (first args)
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
