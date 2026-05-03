(ns build
  "tools.build recipe. `clj -T:build uber` produces target/myapp.jar
   ready for `java -jar`. Compiles all `.clj` under src/ ahead of time
   so the JVM start-up is fast and the AOT'd `myapp.server.main`
   exposes a `-main` for the uberjar manifest."
  (:require [clojure.tools.build.api :as b]))

(def lib       'myapp/myapp)
(def version   (or (System/getenv "MYAPP_VERSION") "0.0.1"))
(def class-dir "target/classes")
(def uber-file (format "target/%s.jar" (name lib)))
(def basis     (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  ;; Copy resources + the prebuilt web bundle so the running jar can
  ;; serve them without a sibling directory on disk. WUN_STATIC defaults
  ;; to /app/public in the Dockerfile; for non-Docker `java -jar` the
  ;; user can point WUN_STATIC at the working dir.
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      @basis
                  :ns-compile '[myapp.server.main]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'myapp.server.main}))
