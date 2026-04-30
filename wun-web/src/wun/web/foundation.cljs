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

;; --- 6.B primitives --------------------------------------------------------

(r/register! :wun/Divider
  (fn [{:keys [thickness]} _children]
    [:hr.wun-divider
     {:style {:border           "0"
              :border-top-width (str (or thickness 1) "px")
              :border-top-style "solid"
              :border-top-color "rgba(0,0,0,0.12)"
              :margin           "8px 0"}}]))

(r/register! :wun/Link
  (fn [{:keys [href on-press]} children]
    (into [:a.wun-link
           {:href     (or href "#")
            :on-click (when on-press
                        (fn [ev]
                          (.preventDefault ev)
                          (bus/dispatch! (:intent on-press)
                                         (:params on-press))))
            :style    {:color           "#0a66c2"
                       :text-decoration "none"
                       :border-bottom   "1px dotted currentColor"}}]
          children)))

(r/register! :wun/Switch
  (fn [{:keys [value on-toggle]} _children]
    [:label.wun-switch
     {:style {:display       "inline-flex"
              :align-items   "center"
              :gap           "6px"
              :cursor        (if on-toggle "pointer" "default")}}
     [:input
      {:type      "checkbox"
       :checked   (boolean value)
       :on-change (when on-toggle
                    (fn [ev]
                      (let [v (.-checked (.-target ev))]
                        (bus/dispatch! (:intent on-toggle)
                                       (assoc (:params on-toggle) :value v)))))}]]))

(r/register! :wun/Badge
  (fn [{:keys [tone]} children]
    (let [tones {:info    {:bg "#e8f1ff" :fg "#0a4ea3"}
                 :success {:bg "#e1f6e9" :fg "#1b6c3a"}
                 :warning {:bg "#fff3d6" :fg "#7a5300"}
                 :danger  {:bg "#fde2e2" :fg "#9b1c1c"}}
          {:keys [bg fg]} (get tones tone (:info tones))]
      (into [:span.wun-badge
             {:style {:display          "inline-block"
                      :padding          "2px 8px"
                      :border-radius    "999px"
                      :font-size        "11px"
                      :font-weight      "600"
                      :letter-spacing   "0.02em"
                      :background-color bg
                      :color            fg}}]
            children))))

(r/register! :wun/Heading
  (fn [{:keys [level]} children]
    (let [tag (case level 1 :h1 2 :h2 3 :h3 4 :h4 :h2)]
      (into [tag {:class "wun-heading" :style {:margin "0"}}] children))))

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
