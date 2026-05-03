(ns wun.web.foundation
  "Web (Replicant) renderers for the foundational `:wun/*` vocabulary.
   User apps register their own renderers the same way -- the registry
   doesn't distinguish framework from user code.

   Phase 3 swap: same hiccup output as before, but event handlers
   move from Reagent's `:on-click` directly-attached-fn to Replicant's
   `:on {:click <fn>}` map -- Replicant's documented idiom and the
   only one its renderer recognises."
  (:require [wun.components     :as components]
            [wun.forms          :as forms]
            [wun.web.renderers  :as r]
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
           (cond-> {:type "button"}
             on-press (assoc :on
                             {:click (fn [_]
                                       (bus/dispatch! (:intent on-press)
                                                      (:params on-press)))}))]
          children)))

;; --- 6.B primitives --------------------------------------------------------

(r/register! :wun/Divider
  (fn [{:keys [thickness]} _children]
    ;; Color, structural border, and margin live in wun.web.styles.
    ;; Per-instance `:thickness` still wins via inline override.
    [:hr.wun-divider
     (cond-> {}
       thickness (assoc :style {:border-top-width (str thickness "px")}))]))

(r/register! :wun/Link
  (fn [{:keys [href on-press]} children]
    ;; Color / text-decoration / border-bottom live in wun.web.styles.
    (into [:a.wun-link
           (cond-> {:href (or href "#")}
             on-press (assoc :on
                             {:click (fn [ev]
                                       (.preventDefault ev)
                                       (bus/dispatch! (:intent on-press)
                                                      (:params on-press)))}))]
          children)))

(r/register! :wun/Switch
  (fn [{:keys [value on-toggle]} _children]
    [:label.wun-switch
     {:style {:display       "inline-flex"
              :align-items   "center"
              :gap           "6px"
              :cursor        (if on-toggle "pointer" "default")}}
     [:input
      (cond-> {:type    "checkbox"
               :checked (boolean value)}
        on-toggle (assoc :on
                         {:change (fn [ev]
                                    (let [v (.-checked (.-target ev))]
                                      (bus/dispatch! (:intent on-toggle)
                                                     (assoc (:params on-toggle)
                                                            :value v))))}))]]))

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
    ;; Margin + color live in wun.web.styles via the .wun-heading rule.
    (let [tag (case level 1 :h1 2 :h2 3 :h3 4 :h4 :h2)]
      (into [tag {:class "wun-heading"}] children))))

;; :wun/Card -- titled container. Optional :title renders as a header
;; bar above the body. Visual styling lives in wun.web.styles via
;; `.wun-card` / `.wun-card__title` / `.wun-card__body`.

(r/register! :wun/Card
  (fn [{:keys [title]} children]
    (cond-> [:section.wun-card]
      title (conj [:header.wun-card__title title])
      true  (conj (into [:div.wun-card__body] children)))))

;; :wun/Image -- straightforward img tag. `:size` becomes width+height.

(r/register! :wun/Image
  (fn [{:keys [src alt size]} _children]
    [:img.wun-image
     (cond-> {:src (or src "")
              :alt (or alt "")}
       size (assoc :width size :height size))]))

;; :wun/Avatar -- circular avatar with image src OR initials fallback.

(r/register! :wun/Avatar
  (fn [{:keys [src initials size]} _children]
    (let [px (or size 32)]
      (if src
        [:span.wun-avatar
         {:style {:width  (str px "px")
                  :height (str px "px")
                  :background-image (str "url('" src "')")}}]
        [:span.wun-avatar.wun-avatar--initials
         {:style {:width  (str px "px")
                  :height (str px "px")
                  :line-height (str px "px")
                  :font-size   (str (max 10 (int (* px 0.4))) "px")}}
         (or initials "?")]))))

;; :wun/List -- vertical list container; structurally a ul, styled
;; flat (no bullets) so individual items style as the parent screen
;; chooses.

(r/register! :wun/List
  (fn [_props children]
    (into [:ul.wun-list] children)))

;; :wun/Spacer -- inert vertical gap. `:size` is in px; defaults to 8.

(r/register! :wun/Spacer
  (fn [{:keys [size]} _children]
    [:div.wun-spacer {:style {:height (str (or size 8) "px")}}]))

;; :wun/ScrollView -- bounded scrolling container. `:direction` chooses
;; `vertical` (default) vs `horizontal`.

(r/register! :wun/ScrollView
  (fn [{:keys [direction]} children]
    (into [:div.wun-scroll
           {:data-direction (some-> direction name)}]
          children)))

;; :wun/Input -- text input bound through an intent. Two-way data flow
;; uses optimistic morphs: the on-input handler dispatches a value-change
;; intent; the local morph reflects it instantly while the server's
;; authoritative state catches up.

(r/register! :wun/Input
  (fn [{:keys [value placeholder on-change]} _children]
    [:input.wun-input
     (cond-> {:type "text"
              :value (or value "")
              :placeholder placeholder}
       on-change (assoc :on
                        {:input (fn [ev]
                                  (let [v (.-value (.-target ev))]
                                    (bus/dispatch! (:intent on-change)
                                                   (assoc (:params on-change)
                                                          :value v))))}))]))

;; :wun/WebFrame -- the capability fallback. The server emits this in
;; place of any subtree containing components the client doesn't
;; advertise. Phase 6 lands real Hotwire Native iOS / Android WebView
;; rendering; on web today it's a styled placeholder since web is the
;; one platform that can render everything in the vocabulary anyway.

(r/register! :wun/ErrorBoundary
  (fn [{:keys [reason]} _children]
    [:div.wun-error
     {:role "alert"
      :style {:padding         "12px 16px"
              :border          "1px solid #f0a"
              :border-radius   "6px"
              :background      "#fff5f8"
              :color           "#9b1c1c"
              :font            "13px ui-monospace, monospace"
              :white-space     "pre-wrap"}}
     [:strong {:style {:display "block" :margin-bottom "4px"}}
      "Render error"]
     reason]))

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

;; :wun/Skeleton -- baseline loading state primitive. Apps render it
;; while waiting for the first SSE frame, or as a placeholder for
;; subtrees the server hasn't computed yet.

(r/register! :wun/Skeleton
  (fn [{:keys [width height]} _children]
    ;; Background gradient + shimmer animation live in wun.web.styles.
    ;; Per-instance width/height stays inline so callers can size each
    ;; skeleton block to the content it stands in for.
    [:div.wun-skeleton
     {:style {:width  (or width "100%")
              :height (or height "12px")}}]))

;; --- Phase 4: form / field / file-input renderers --------------------------

(r/register! :wun/Form
  (fn [{:keys [id]} children]
    (into [:form.wun-form
           {:on {:submit (fn [ev]
                           (.preventDefault ev)
                           (bus/dispatch! :wun.forms/submit {:form id}))}}]
          children)))

(r/register! :wun/Field
  (fn [{:keys [form name type label placeholder]} _children]
    (let [state @bus/confirmed-state
          value (or (forms/field-value state form name) "")
          err   (forms/field-error state form name)
          touched? (forms/field-touched? state form name)]
      [:label.wun-field
       {:style {:display "flex" :flex-direction "column" :gap "4px"}}
       (when label [:span.wun-field__label label])
       [:input.wun-input
        {:type        (or type "text")
         :value       value
         :placeholder placeholder
         :name        (clojure.core/name name)
         :on          {:input (fn [ev]
                                (let [v (.-value (.-target ev))]
                                  (bus/dispatch! :wun.forms/change
                                                 {:form  form
                                                  :field name
                                                  :value v})))
                       :blur  (fn [_]
                                (bus/dispatch! :wun.forms/touch
                                               {:form form :field name}))}}]
       (when (and err touched?)
         [:span.wun-field__error
          {:style {:color "#9b1c1c" :font-size "12px"}}
          (str err)])])))

(r/register! :wun/FileInput
  (fn [{:keys [form field accept multiple]} _children]
    (let [state @bus/confirmed-state
          ;; Render any active uploads bound to this field as a
          ;; progress strip below the picker.
          actives (->> (:uploads state)
                       vals
                       (filter #(and (= form  (:form  %))
                                     (= field (:field %))))
                       (sort-by :upload-id))]
      (into [:div.wun-fileinput
             [:input
              {:type "file"
               :accept accept
               :multiple (boolean multiple)
               :on {:change (fn [ev]
                              (let [files (.. ev -target -files)
                                    n     (.-length files)]
                                (dotimes [i n]
                                  (let [f (.item files i)]
                                    (bus/start-upload! form field f)))))}}]]
            (for [u actives]
              [:div.wun-fileinput__progress
               {:style {:display "flex" :align-items "center" :gap "8px"
                        :font "12px ui-monospace, monospace"
                        :margin-top "4px"}}
               [:span (:filename u)]
               (case (:status u)
                 :complete [:span {:style {:color "#1b6c3a"}} "uploaded"]
                 :errored  [:span {:style {:color "#9b1c1c"}}
                            (str "error: " (:error u))]
                 [:progress
                  {:max (or (:size u) 0)
                   :value (or (:received u) 0)
                   :style {:flex 1}}])])))))

;; ---------------------------------------------------------------------------
;; Strict-mode parity check.
;;
;; Capability fallback (a `:wun/WebFrame` placeholder) is the right
;; design for *user* components a given client doesn't bind. For the
;; framework's own `:wun/*` vocabulary, missing a renderer is a bug —
;; the WebFrame fallback only hides it. Crash at load time with the
;; full list so the gap can't survive into a running app.
;;
;; Runs once on ns load, after every renderer above is registered.

(let [missing (->> (components/registered)
                   (filter #(= "wun" (namespace %)))
                   (remove r/lookup)
                   sort
                   vec)]
  (when (seq missing)
    (throw (ex-info
             (str "wun.web.foundation: framework :wun/* components without "
                  "a web renderer: " missing
                  ". Add `(r/register! " (first missing) " ...)` "
                  "in this ns — the WebFrame capability fallback is for "
                  "user-namespace components, not the framework's own "
                  "vocabulary.")
             {:missing missing}))))
