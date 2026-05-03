(ns myapp.web.showcase-renderers
  "Web renderers for showcase-only components. Currently just
   `:myapp.showcase/RichEditor`, which is intentionally unbound on
   iOS / Android so those clients exercise the WebFrame fallback
   on the same screen."
  (:require [wun.web.renderers :as r]))

(r/register! :myapp.showcase/RichEditor
  (fn [{:keys [html]} _children]
    [:div.showcase-rich-editor
     {:style {:padding       "16px"
              :border        "1px solid rgba(0,0,0,0.12)"
              :border-radius "10px"
              :background    "rgba(10,102,194,0.04)"
              :font          "14px/1.5 ui-sans-serif, system-ui, sans-serif"
              :color         "#0a1020"}}
     [:strong {:style {:display "block" :margin-bottom "6px"}}
      "RichEditor (web-only renderer)"]
     [:pre {:style {:margin    "0"
                    :font-size "12px"
                    :white-space "pre-wrap"}}
      (or html "<empty>")]]))
