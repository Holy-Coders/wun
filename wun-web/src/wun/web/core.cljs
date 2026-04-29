(ns wun.web.core
  "Wun web phase-0 client.

   Connects to the server's SSE patch stream, mirrors the UI tree in an
   atom, and renders Hiccup to vanilla DOM via a tiny component-keyword
   dispatch. User actions are POSTed back to the server as transit-json
   intents.

   The brief calls for reagent. Phase 0 ships with a hand-rolled DOM
   renderer because reagent lives on Clojars and this sandbox can't reach
   it. Phase 1 swaps reagent in -- the renderer is the only thing that
   changes; the SSE wiring, patch applicator, intent dispatcher, and
   component vocabulary are stack-agnostic."
  (:require [cognitect.transit :as transit]))

;; ---------------------------------------------------------------------------
;; Config

(def server-base
  (or (some-> js/window .-WUN_SERVER) "http://localhost:8080"))

;; ---------------------------------------------------------------------------
;; Transit

(def ^:private writer (transit/writer :json))
(def ^:private reader (transit/reader :json))

(defn- t->str [v] (transit/write writer v))
(defn- str->t [s] (transit/read reader s))

;; ---------------------------------------------------------------------------
;; Local mirror of the server's UI tree.

(defonce tree-state (atom nil))

(defn- ^js status-el [] (.getElementById js/document "status"))
(defn- ^js app-el    [] (.getElementById js/document "app"))

(defn- set-status! [s]
  (when-let [el (status-el)] (set! (.-textContent el) s)))

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
;; Renderer. Hiccup -> DOM. Anything we don't understand becomes a visible
;; placeholder; in phase 2 this is where the WebFrame fallback lives on web.

(declare render-node)

(defn- el [tag] (.createElement js/document tag))

(defn- props+children [v]
  (let [maybe-props (second v)]
    (if (map? maybe-props)
      [maybe-props (drop 2 v)]
      [{}          (rest v)])))

(defn- append-children! [^js parent children]
  (doseq [c children]
    (when-some [n (render-node c)]
      (.appendChild parent n))))

(defmulti render-component (fn [tag _props _children] tag))

(defmethod render-component :default [tag _props children]
  (let [n (el "div")]
    (set! (.-className n) "wun-unknown")
    (set! (.-textContent n) (str "[unknown component " tag "]"))
    (append-children! n children)
    n))

(defmethod render-component :wun/Stack [_ {:keys [direction gap padding]} children]
  (let [n (el "div")
        s (.-style n)]
    (set! (.-className n) "wun-stack")
    (when direction
      (.setAttribute n "data-direction" (name direction)))
    (when gap     (set! (.-gap     s) (str gap "px")))
    (when padding (set! (.-padding s) (str padding "px")))
    (append-children! n children)
    n))

(defmethod render-component :wun/Text [_ {:keys [variant]} children]
  (let [tag (case variant :h1 "h1" :h2 "h2" "p")
        n   (el tag)]
    (set! (.-className n)
          (case variant
            :h1 "wun-text wun-text--h1"
            :h2 "wun-text wun-text--h2"
            "wun-text wun-text--body"))
    (append-children! n children)
    n))

(defmethod render-component :wun/Button [_ {:keys [on-press]} children]
  (let [n (el "button")]
    (set! (.-className n) "wun-button")
    (.setAttribute n "type" "button")
    (when on-press
      (.addEventListener n "click"
        (fn [_] (dispatch-intent! (:intent on-press) (:params on-press)))))
    (append-children! n children)
    n))

(defn render-node [node]
  (cond
    (nil? node)     nil
    (string? node)  (.createTextNode js/document node)
    (number? node)  (.createTextNode js/document (str node))
    (boolean? node) (.createTextNode js/document (str node))
    (vector? node)
    (let [tag (first node)]
      (if (keyword? tag)
        (let [[props children] (props+children node)]
          (render-component tag props children))
        ;; Plain seq of nodes -> wrap in a fragment-y div.
        (let [n (el "div")]
          (.setAttribute n "data-wun-fragment" "")
          (append-children! n node)
          n)))
    :else (.createTextNode js/document (str node))))

(defn- mount-tree! [tree]
  (when-let [root (app-el)]
    (set! (.-innerHTML root) "")
    (when-some [n (render-node tree)]
      (.appendChild root n))))

;; React to mirror updates.
(add-watch tree-state ::mount
  (fn [_ _ _ new-tree] (mount-tree! new-tree)))

;; ---------------------------------------------------------------------------
;; Patch application. Phase 0 only honours :replace at root.

(defn- apply-patch! [{:keys [op path value]}]
  (case op
    :replace (if (empty? path)
               (reset! tree-state value)
               (js/console.warn "wun: non-root :replace not implemented" (clj->js path)))
    (js/console.warn "wun: unsupported op" (str op))))

(defn- apply-envelope! [{:keys [patches status error]}]
  (when (= status :error)
    (js/console.error "wun: server error" (clj->js error)))
  (doseq [p patches] (apply-patch! p)))

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
    (.addEventListener src "open"
      (fn [_] (set-status! "connected")))
    (.addEventListener src "error"
      (fn [_] (set-status! "disconnected (browser will retry)")))
    (reset! es src)))

;; ---------------------------------------------------------------------------
;; Entry points.

(defn ^:export init []
  (start-sse!))

(defn ^:export after-reload []
  ;; Re-mount with current state so dev reload survives.
  (mount-tree! @tree-state))
