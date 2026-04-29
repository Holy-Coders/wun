(ns wun.web.foundation
  "Web DOM renderers for the foundational `:wun/*` vocabulary. User
   apps register their own renderers the same way -- the registry
   doesn't distinguish framework from user code."
  (:require [wun.web.renderers :as r]
            [wun.web.intent-bus :as bus]))

(defn- append-all! [^js parent kids]
  (doseq [c kids] (.appendChild parent c)))

;; :wun/Stack -- vertical by default; horizontal when :direction = :row.

(r/register! :wun/Stack
  (fn [{:keys [direction gap padding]} children]
    (let [n (.createElement js/document "div")
          s (.-style n)]
      (set! (.-className n) "wun-stack")
      (when direction
        (.setAttribute n "data-direction" (name direction)))
      (when gap     (set! (.-gap     s) (str gap     "px")))
      (when padding (set! (.-padding s) (str padding "px")))
      (append-all! n children)
      n)))

;; :wun/Text -- variant maps to a tag + class.

(r/register! :wun/Text
  (fn [{:keys [variant]} children]
    (let [tag (case variant :h1 "h1" :h2 "h2" "p")
          n   (.createElement js/document tag)]
      (set! (.-className n)
            (case variant
              :h1 "wun-text wun-text--h1"
              :h2 "wun-text wun-text--h2"
              "wun-text wun-text--body"))
      (append-all! n children)
      n)))

;; :wun/Button -- :on-press is an intent reference, dispatched through
;; the intent bus so the SSE wiring lives in one place.

(r/register! :wun/Button
  (fn [{:keys [on-press]} children]
    (let [n (.createElement js/document "button")]
      (set! (.-className n) "wun-button")
      (.setAttribute n "type" "button")
      (when on-press
        (.addEventListener n "click"
          (fn [_] (bus/dispatch! (:intent on-press) (:params on-press)))))
      (append-all! n children)
      n)))
