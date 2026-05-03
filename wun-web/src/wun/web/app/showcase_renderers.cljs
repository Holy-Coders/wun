(ns wun.web.app.showcase-renderers
  "Web binding for the built-in showcase demo's
   `:wun.app.showcase/RichEditor`. Sister of
   `templates/fragments/showcase/src/myapp/web/showcase_renderers.cljs`
   for the framework's own `wun dev` loop. iOS / Android intentionally
   leave this component unbound so the page exercises the WebFrame
   capability fallback."
  (:require [wun.web.renderers :as r]))

(r/register! :wun.app.showcase/RichEditor
  (fn [{:keys [html]} _children]
    [:div.showcase-rich-editor
     {:style {:padding       "16px"
              :border        "1px solid var(--wun-border)"
              :border-radius "10px"
              :background    "var(--wun-primary-soft)"
              :font          "14px/1.5 ui-sans-serif, system-ui, sans-serif"
              :color         "var(--wun-text)"}}
     [:strong {:style {:display "block" :margin-bottom "6px"}}
      "RichEditor (web-only renderer)"]
     [:pre {:style {:margin    "0"
                    :font-size "12px"
                    :white-space "pre-wrap"}}
      (or html "<empty>")]]))
