(ns wun.web.core
  "Wun web phase-0 client.

   Connects to the server's SSE patch stream, mirrors the UI tree in a
   reagent atom, and renders Hiccup via a tiny component-keyword dispatch.
   User actions are POSTed back to the server as transit-json intents."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [cognitect.transit :as transit]))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  "Server origin. Override at the JS console with `window.WUN_SERVER`
   when you want to point the client at a remote host."
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; ---------------------------------------------------------------------------
;; Transit

(def ^:private writer (transit/writer :json))
(def ^:private reader (transit/reader :json))

(defn- t->str [v] (transit/write writer v))
(defn- str->t [s] (transit/read reader s))

;; ---------------------------------------------------------------------------
;; Local mirror of the server's UI tree.

(defonce tree-state (r/atom nil))
(defonce status-el  (delay (.getElementById js/document "status")))

(defn- set-status! [s]
  (when-let [el @status-el] (set! (.-textContent el) s)))

;; ---------------------------------------------------------------------------
;; Patch application. Phase 0 only honours :replace at root. Future phases
;; will support arbitrary paths and :insert / :remove ops.

(defn- apply-patch! [{:keys [op path value]}]
  (case op
    :replace (if (empty? path)
               (reset! tree-state value)
               (do (js/console.warn "wun: non-root :replace not implemented" (clj->js path))
                   nil))
    (js/console.warn "wun: unsupported op" (str op))))

(defn- apply-envelope! [{:keys [patches status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (doseq [p patches] (apply-patch! p)))

;; ---------------------------------------------------------------------------
;; Intent dispatch.

(defn dispatch-intent! [intent params]
  (let [id   (random-uuid)
        body (t->str {:intent intent :params (or params {}) :id id})]
    (-> (js/fetch (str server-base "/intent")
                  #js {:method  "POST"
                       :headers #js {"Content-Type" "application/transit+json"}
                       :body    body})
        (.catch (fn [e] (js/console.error "wun: intent failed" e))))
    id))

;; ---------------------------------------------------------------------------
;; Renderer. Component vocabulary -> DOM. Unknown components fall through
;; to a visible placeholder. In phase 2 this is where the WebFrame fallback
;; lives on web (an iframe / Turbo frame).

(declare render-node)

(defn- component-tag [v]
  (when (and (vector? v) (keyword? (first v))) (first v)))

(defn- props+children [[_ maybe-props & rest]]
  (if (map? maybe-props)
    [maybe-props rest]
    [{} (cons maybe-props rest)]))

(defn- render-children [children]
  (map-indexed (fn [i c] ^{:key i} [render-node c]) children))

(defmulti render-component (fn [tag _props _children] tag))

(defmethod render-component :default [tag _props children]
  (into [:div.wun-unknown {} (str "[unknown component " tag "]")]
        (render-children children)))

(defmethod render-component :wun/Stack [_ {:keys [direction gap padding]} children]
  (into [:div.wun-stack
         {:data-direction (when direction (name direction))
          :style {:gap (or gap 8)
                  :padding padding}}]
        (render-children children)))

(defmethod render-component :wun/Text [_ {:keys [variant]} children]
  (let [class (case variant
                :h1 "wun-text wun-text--h1"
                :h2 "wun-text wun-text--h2"
                "wun-text wun-text--body")
        tag   (case variant
                :h1 :h1
                :h2 :h2
                :p)]
    (into [tag {:class class}] (render-children children))))

(defmethod render-component :wun/Button [_ {:keys [on-press]} children]
  (let [handler (when on-press
                  #(dispatch-intent! (:intent on-press) (:params on-press)))]
    (into [:button.wun-button {:on-click handler :type "button"}]
          (render-children children))))

(defn render-node [node]
  (cond
    (nil? node)     nil
    (string? node)  node
    (number? node)  (str node)
    (boolean? node) (str node)
    (vector? node)
    (if-let [tag (component-tag node)]
      (let [[props children] (props+children node)]
        [render-component tag props children])
      (into [:<>] (render-children node)))
    :else (str node)))

(defn app []
  (if-let [tree @tree-state]
    [render-node tree]
    [:p.wun-text.wun-text--body "Waiting for first patch from server..."]))

;; ---------------------------------------------------------------------------
;; SSE wiring.

(defonce ^:private es (atom nil))

(defn- start-sse! []
  (when-let [old @es] (.close old))
  (let [src (js/EventSource. (str server-base "/wun"))]
    (.addEventListener src "patch"
      (fn [ev]
        (set-status! "connected")
        (apply-envelope! (str->t (.-data ev)))))
    (.addEventListener src "error"
      (fn [_] (set-status! "disconnected (browser will retry)")))
    (reset! es src)))

;; ---------------------------------------------------------------------------
;; Mount.

(defonce ^:private root (atom nil))

(defn- mount! []
  (when-not @root
    (reset! root (rdc/create-root (.getElementById js/document "app"))))
  (rdc/render @root [app]))

(defn ^:export init []
  (mount!)
  (start-sse!))

(defn ^:export after-reload []
  (mount!))
