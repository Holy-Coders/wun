(ns wun.web.foundation
  "Web (reagent) renderers for the foundational `:wun/*` vocabulary.
   User apps register their own renderers the same way -- the registry
   doesn't distinguish framework from user code."
  (:require [wun.web.renderers  :as r]
            [wun.web.intent-bus :as bus]))

;; :wun/Stack -- vertical by default; horizontal when :direction = :row.

(r/register! :wun/Stack
  (fn [{:keys [direction gap padding]} children]
    (into [:div.wun-stack
           {:data-direction (some-> direction name)
            :style          (cond-> {}
                              gap     (assoc :gap     (str gap     "px"))
                              padding (assoc :padding (str padding "px")))}]
          children)))

;; :wun/Text -- variant maps to a tag + class.

(r/register! :wun/Text
  (fn [{:keys [variant]} children]
    (let [base-tag (case variant
                     :h1 :h1
                     :h2 :h2
                     :p)
          klass    (case variant
                     :h1 "wun-text wun-text--h1"
                     :h2 "wun-text wun-text--h2"
                     "wun-text wun-text--body")]
      (into [base-tag {:class klass}] children))))

;; :wun/Button -- :on-press is an intent reference, dispatched through
;; the intent bus so the SSE wiring lives in one place.

(r/register! :wun/Button
  (fn [{:keys [on-press]} children]
    (into [:button.wun-button
           {:type     "button"
            :on-click (when on-press
                        (fn [_]
                          (bus/dispatch! (:intent on-press)
                                         (:params on-press))))}]
          children)))

;; :wun/WebFrame -- the capability fallback. The server emits this in
;; place of any subtree containing components the client doesn't
;; advertise. Phase 2 (iOS / Android) renders this as a real
;; Hotwire-Native iframe; on web today it's a styled placeholder
;; since web is the one platform that can render everything in the
;; vocabulary anyway.

(r/register! :wun/WebFrame
  (fn [{:keys [src missing reason]} _children]
    [:div.wun-webframe
     {:style {:padding         "12px 14px"
              :border          "1px dashed currentColor"
              :border-radius   "8px"
              :font            "13px ui-monospace, monospace"
              :opacity         0.7}}
     [:strong "WebFrame fallback"]
     (when missing
       [:span " — missing renderer for " [:code (str missing)]])
     (when reason
       [:span " — " reason])
     (when src
       [:span " — src=" [:code src]])]))
