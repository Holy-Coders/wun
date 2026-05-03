(ns wun.errors
  "Error boundary primitives. When a screen's `:render` fn throws,
   the framework catches it and ships an error tree instead of
   tearing down the SSE stream / crashing the client. The user sees
   `:wun/ErrorBoundary` with the error message; the server's
   telemetry sink sees a `:wun/intent.rejected`-flavoured event so
   the deployer can alert.

   Why a separate namespace from screens: the error boundary needs
   to live both on the server (where it wraps `screens/render` in
   the broadcast path) and on the client (where it wraps the same
   render in the optimistic-prediction path). Cljc keeps the two
   honest.

   Production posture: never echo a raw exception message to a
   non-developer client. The default error tree prints
   `(.getMessage e)` because the development experience needs it;
   apps wire `set-error-formatter!` to a function that returns a
   safe-to-show string in production builds."
  #?(:clj  (:require [clojure.string :as str])
     :cljs (:require [clojure.string :as str])))

;; ---------------------------------------------------------------------------
;; Formatter

(defonce ^:private error-formatter
  (atom (fn default-formatter [^Throwable t]
          (let [msg  (or #?(:clj (.getMessage t) :cljs (.-message t))
                         "(no message)")
                kind #?(:clj  (.getName (class t))
                        :cljs (.-name (or (.-constructor t) #js {})))]
            (str (or kind "error") ": " msg)))))

(defn set-error-formatter!
  "Replace the default error formatter `(throwable) -> string`. Apps
   that ship a release build hide stack traces here; debug builds
   pretty-print the cause."
  [f]
  (reset! error-formatter f))

(defn format-error [t]
  (try (@error-formatter t)
       (catch #?(:clj Throwable :cljs :default) _
         "(error formatter failed)")))

;; ---------------------------------------------------------------------------
;; Error tree

(defn error-tree
  "The Hiccup tree the framework renders when a screen blows up.
   Components: `[:wun/ErrorBoundary {:reason ...}]` is registered
   server-side as a foundational component; client renderers
   (web/iOS/Android) all dispatch on it and produce a visible
   diagnostic. Apps override the look by re-registering
   `:wun/ErrorBoundary` in their own components.cljc."
  [t]
  [:wun/ErrorBoundary {:reason (format-error t)}])

;; ---------------------------------------------------------------------------
;; Boundary helpers

(defn safe-render
  "Run `render-fn` against `state`, returning a Hiccup tree. If
   `render-fn` throws, returns the error tree and calls `on-throw`
   (a side-effecting telemetry hook) with the throwable."
  [render-fn state on-throw]
  (try (render-fn state)
       (catch #?(:clj Throwable :cljs :default) t
         (when on-throw (on-throw t))
         (error-tree t))))
