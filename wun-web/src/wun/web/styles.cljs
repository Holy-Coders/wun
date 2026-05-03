(ns wun.web.styles
  "Default stylesheet for the foundational `:wun/*` web renderers.

   Wun's renderers in `wun.web.foundation` emit semantic class names
   (`.wun-stack`, `.wun-button`, `.wun-text--*`, `.wun-link`, …) and
   keep inline styles to a minimum. This namespace ships the default
   rules for those classes as a single CSS string and injects it
   into the document head on `wun.web.core/init` so apps work
   without configuration.

   Theme cascading works through CSS custom properties:
     :root {
       --wun-primary:        #0a66c2;
       --wun-primary-soft:   color-mix(in srgb, var(--wun-primary) 14%, transparent);
       --wun-text:           #0a1020;
       …
     }
   Each rule below references those vars with sensible fallbacks, so
   an app that does *nothing* gets the default Wun blue, and an app
   that ships its own `styles.css` (the `wun new app` scaffold does)
   wins via the cascade.

   Apps that want to fully replace the defaults can include a
   `<style id=\"wun-foundation-css\">…</style>` in the document head
   *before* the bundle loads — `inject!` is a no-op when an element
   with that id already exists. The marker class is hard-coded so
   the override path is visible in the DOM."
  (:require [clojure.string :as str]))

(def ^:private element-id "wun-foundation-css")

(def default-css
  (str/join
   "\n"
   [":root {"
    "  --wun-primary:        #0a66c2;"
    "  --wun-primary-soft:   color-mix(in srgb, #0a66c2 14%, transparent);"
    "  --wun-primary-strong: color-mix(in srgb, #0a66c2 80%, black 20%);"
    "  --wun-text:           #0a1020;"
    "  --wun-text-muted:     rgba(10, 16, 32, 0.65);"
    "  --wun-bg:             #ffffff;"
    "  --wun-bg-soft:        #f6f7fb;"
    "  --wun-border:         rgba(10, 16, 32, 0.12);"
    "  --wun-success:        #16a34a;"
    "  --wun-warning:        #b45309;"
    "  --wun-danger:         #b91c1c;"
    "}"
    "@media (prefers-color-scheme: dark) {"
    "  :root {"
    "    --wun-text:       #e6eaf2;"
    "    --wun-text-muted: rgba(230, 234, 242, 0.7);"
    "    --wun-bg:         #0c1220;"
    "    --wun-bg-soft:    #131b2e;"
    "    --wun-border:     rgba(230, 234, 242, 0.14);"
    "  }"
    "}"
    ".wun-stack { display: flex; flex-direction: column; }"
    ".wun-stack[data-direction=\"row\"] { flex-direction: row; align-items: center; }"
    ".wun-button {"
    "  font: inherit; padding: 6px 14px; border-radius: 6px;"
    "  border: 1px solid var(--wun-primary);"
    "  color: var(--wun-primary); background: transparent;"
    "  cursor: pointer; transition: background 120ms ease, color 120ms ease;"
    "}"
    ".wun-button:hover { background: var(--wun-primary-soft); }"
    ".wun-button:active { background: var(--wun-primary); color: var(--wun-bg); }"
    ".wun-text--h1   { font-size: 32px; font-weight: 600; margin: 0; color: var(--wun-text); }"
    ".wun-text--h2   { font-size: 22px; font-weight: 600; margin: 0; color: var(--wun-text); }"
    ".wun-text--body { font-size: 15px; margin: 0; color: var(--wun-text-muted); }"
    ".wun-link {"
    "  color: var(--wun-primary); text-decoration: none;"
    "  border-bottom: 1px dotted currentColor;"
    "}"
    ".wun-heading { margin: 0; color: var(--wun-text); }"
    ".wun-input {"
    "  font: inherit; padding: 6px 10px; border-radius: 6px;"
    "  border: 1px solid var(--wun-border);"
    "  background: var(--wun-bg); color: var(--wun-text);"
    "}"
    ".wun-divider {"
    "  border: 0; border-top: 1px solid var(--wun-border); margin: 8px 0;"
    "}"
    ".wun-skeleton {"
    "  display: inline-block; border-radius: 4px;"
    "  background: linear-gradient(90deg,"
    "    color-mix(in srgb, var(--wun-text) 6%, transparent) 0%,"
    "    color-mix(in srgb, var(--wun-text) 14%, transparent) 50%,"
    "    color-mix(in srgb, var(--wun-text) 6%, transparent) 100%);"
    "  background-size: 200% 100%;"
    "  animation: wun-skeleton-shimmer 1.4s ease-in-out infinite;"
    "}"
    "@keyframes wun-skeleton-shimmer {"
    "  0%   { background-position: 200% 0; }"
    "  100% { background-position: -200% 0; }"
    "}"]))

(defn inject!
  "Inject the default foundation CSS as a `<style>` element in the
   document head. No-op when an element with the override id already
   exists (so apps can ship their own block before the bundle loads,
   or include a stylesheet that defines the same rules with higher
   specificity). Idempotent."
  []
  (when-let [doc js/document]
    (when-not (.getElementById doc element-id)
      (let [el (.createElement doc "style")]
        (set! (.-id el) element-id)
        (set! (.-textContent el) default-css)
        (-> doc .-head (.appendChild el))))))
