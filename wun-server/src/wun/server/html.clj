(ns wun.server.html
  "Server-side Hiccup -> HTML for the WebFrame fallback. When a native
   client lacks a renderer for a component, the server embeds the
   actual rendered subtree in HTML and the WKWebView shows it -- so
   the user sees the missing component's content rather than a stub
   diagnostic.

   The component-to-HTML mapping mirrors the SwiftUI mapping in
   `wun-ios/Sources/Wun/Foundation/*Renderer.swift` semantically: a
   :wun/Stack lays out flex children, :wun/Text picks a tag from the
   variant, etc. Anything not in the framework's vocabulary falls
   through to a `[unknown ...]` block so user-defined components
   show *something*.

   Phase 1.I ships static rendering only -- the WebFrame doesn't get
   live updates inside it. A later slice could embed a live cljs
   renderer for true component-level interactivity."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- escape [s]
  (-> (str s)
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&#39;")))

(defn- has-props? [v]
  (and (vector? v) (>= (count v) 2) (map? (second v))))

(defn- props-of [v]    (when (has-props? v) (second v)))
(defn- children-of [v] (if (has-props? v) (drop 2 v) (rest v)))

(declare render-node)

(defn- render-children [children]
  (apply str (map render-node children)))

;; ---------------------------------------------------------------------------
;; Component dispatch

(defmulti render-component (fn [tag _props _children] tag))

(defmethod render-component :default [tag _ children]
  (str "<div class='wun-unknown'>"
       "<small>[unknown component <code>" (escape (str tag)) "</code>]</small>"
       (render-children children)
       "</div>"))

(defmethod render-component :wun/Stack [_ {:keys [direction gap padding]} children]
  (let [dir   (if (= direction :row) "row" "column")
        style (cond-> (str "display:flex; flex-direction:" dir ";")
                gap     (str " gap:"     gap     "px;")
                padding (str " padding:" padding "px;"))]
    (str "<div class='wun-stack' style='" style "'>" (render-children children) "</div>")))

(defmethod render-component :wun/Text [_ {:keys [variant]} children]
  (let [tag   (case variant :h1 "h1" :h2 "h2" "p")
        klass (case variant
                :h1 "wun-text wun-text--h1"
                :h2 "wun-text wun-text--h2"
                "wun-text wun-text--body")]
    (str "<" tag " class='" klass "'>" (render-children children) "</" tag ">")))

(defn- kw->wire [x]
  (if (keyword? x)
    (if-let [n (namespace x)]
      (str n "/" (name x))
      (name x))
    x))

(defn- intent-attrs
  "Common HTML data-attributes for any element that carries an
   `:on-press`-shaped intent ref. The page-level bridge script wires
   click handlers to whatever's annotated with these. Params are
   inlined as a JSON-encoded string so the script doesn't need to
   parse Hiccup."
  [intent-ref]
  (let [intent (some-> intent-ref :intent str)
        params (or (:params intent-ref) {})]
    (str " data-wun-intent='" (escape (or intent "")) "' "
         "data-wun-params='"  (escape (json/write-str
                                       (walk/postwalk kw->wire params))) "'")))

(defmethod render-component :wun/Button [_ {:keys [on-press]} children]
  (let [intent (some-> on-press :intent str)]
    (str "<button class='wun-button'"
         (intent-attrs on-press)
         " title='Intent: " (escape (or intent "(none)")) "'>"
         (render-children children) "</button>")))

(defmethod render-component :wun/Card [_ {:keys [title]} children]
  (str "<div class='wun-card'>"
       (when title (str "<h3 class='wun-card-title'>" (escape title) "</h3>"))
       (render-children children) "</div>"))

(defmethod render-component :wun/Avatar [_ {:keys [src initials size]} _]
  (let [s (or size 40)]
    (str "<div class='wun-avatar' style='width:" s "px; height:" s "px;'>"
         (cond
           src      (str "<img src='" (escape src) "'/>")
           initials (str "<span>" (escape initials) "</span>")
           :else    "?")
         "</div>")))

(defmethod render-component :wun/Image [_ {:keys [src alt size]} _]
  (let [size-attr (when size (str " width='" size "' height='" size "'"))]
    (str "<img class='wun-image' src='" (escape (or src "")) "' "
         "alt='" (escape (or alt "")) "'" size-attr "/>")))

(defmethod render-component :wun/Input [_ {:keys [value placeholder]} _]
  (str "<input class='wun-input' type='text' "
       "value='" (escape (or value "")) "' "
       "placeholder='" (escape (or placeholder "")) "'/>"))

(defmethod render-component :wun/List [_ _ children]
  (str "<div class='wun-list'>" (render-children children) "</div>"))

(defmethod render-component :wun/Spacer [_ {:keys [size]} _]
  (str "<div class='wun-spacer' style='min-width:" (or size 8) "px; "
       "min-height:" (or size 8) "px;'></div>"))

(defmethod render-component :wun/ScrollView [_ {:keys [direction]} children]
  (let [overflow (if (= direction :horizontal) "overflow-x:auto;" "overflow-y:auto;")]
    (str "<div class='wun-scroll' style='" overflow "'>" (render-children children) "</div>")))

;; --- 6.B primitives --------------------------------------------------------

(defmethod render-component :wun/Divider [_ {:keys [thickness]} _]
  (str "<hr class='wun-divider' style='border:0;"
       "border-top:" (or thickness 1) "px solid rgba(0,0,0,0.12);"
       "margin:8px 0;'/>"))

(defmethod render-component :wun/Link [_ {:keys [href on-press]} children]
  (str "<a class='wun-link' href='" (escape (or href "#")) "'"
       (when on-press (intent-attrs on-press)) ">"
       (render-children children) "</a>"))

(defmethod render-component :wun/Switch [_ {:keys [value]} _]
  (str "<input class='wun-switch' type='checkbox'"
       (when value " checked") " disabled/>"))

(defmethod render-component :wun/Badge [_ {:keys [tone]} children]
  (let [klass (str "wun-badge wun-badge--" (or (some-> tone name) "info"))]
    (str "<span class='" klass "'>" (render-children children) "</span>")))

(defmethod render-component :wun/Heading [_ {:keys [level]} children]
  (let [tag (case (or level 2) 1 "h1" 2 "h2" 3 "h3" 4 "h4" "h2")]
    (str "<" tag " class='wun-heading'>" (render-children children) "</" tag ">")))

;; ---------------------------------------------------------------------------
;; Public entry

(defn render-node [node]
  (cond
    (nil? node)     ""
    (string? node)  (escape node)
    (number? node)  (str node)
    (boolean? node) (str node)
    (vector? node)
    (let [tag (first node)]
      (if (keyword? tag)
        (render-component tag (props-of node) (children-of node))
        (apply str (map render-node node))))
    :else (escape (str node))))

(def ^:private base-styles
  (str "html,body{margin:0;padding:0;font-family:ui-sans-serif,system-ui,sans-serif;"
       "background:#fafafa;color:#222;}"
       "body{padding:24px;}"
       ".wun-text{margin:0;}"
       ".wun-text--h1{font-size:32px;font-weight:600;}"
       ".wun-text--h2{font-size:22px;font-weight:600;}"
       ".wun-text--body{font-size:15px;opacity:0.85;}"
       ".wun-button{font:inherit;padding:6px 14px;border-radius:6px;"
       "border:1px solid currentColor;background:transparent;cursor:pointer;}"
       ".wun-button:hover{background:rgba(0,0,0,0.05);}"
       ".wun-card{padding:16px;background:#f4f4f4;border-radius:12px;}"
       ".wun-card-title{margin:0 0 8px;font-size:18px;font-weight:600;}"
       ".wun-avatar{border-radius:50%;background:#ddd;display:flex;"
       "align-items:center;justify-content:center;overflow:hidden;}"
       ".wun-avatar img{width:100%;height:100%;object-fit:cover;}"
       ".wun-input{font:inherit;padding:6px 10px;border-radius:6px;border:1px solid #aaa;}"
       ".wun-image{max-width:100%;}"
       ".wun-unknown{color:crimson;font-family:ui-monospace,monospace;}"
       ".wun-link{color:#0a66c2;text-decoration:none;border-bottom:1px dotted currentColor;}"
       ".wun-badge{display:inline-block;padding:2px 8px;border-radius:999px;"
       "font-size:11px;font-weight:600;letter-spacing:0.02em;}"
       ".wun-badge--info{background:#e8f1ff;color:#0a4ea3;}"
       ".wun-badge--success{background:#e1f6e9;color:#1b6c3a;}"
       ".wun-badge--warning{background:#fff3d6;color:#7a5300;}"
       ".wun-badge--danger{background:#fde2e2;color:#9b1c1c;}"
       ".wun-heading{margin:0;}"))

(def ^:private bridge-script
  "Page-level bridge wiring. Click handlers on any element marked with
   data-wun-intent fire that intent through:

     1. window.WunBridge.dispatch(intent, params)  -- when present.
        The native host (WKWebView via scriptMessageHandler `wunDispatch`,
        Android WebView via JavaScriptInterface `WunBridge`) provides
        this and routes the intent through its native IntentDispatcher
        so the round-trip rides the same SSE connection the rest of
        the app already uses.

     2. fetch('/intent', ...) fallback for desktop browsers.

   The script is intentionally dependency-free vanilla JS so the
   WebFrame fallback works in even the most stripped-down WKWebView."
  "<script>
   (function(){
     function dispatch(intent, params){
       try {
         if (window.WunBridge && typeof window.WunBridge.dispatch === 'function') {
           window.WunBridge.dispatch(intent, params || {});
           return;
         }
       } catch (e) { console.error('wun: WunBridge.dispatch threw', e); }
       fetch('/intent', {
         method:  'POST',
         headers: {'Content-Type': 'application/json'},
         body:    JSON.stringify({intent: intent, params: params || {}, id: cryptoId()}),
       }).catch(function(e){ console.error('wun: /intent fetch failed', e); });
     }
     function cryptoId(){
       if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
       return 'id-' + Math.random().toString(16).slice(2) + '-' + Date.now();
     }
     document.addEventListener('click', function(ev){
       var t = ev.target;
       while (t && t.dataset && !t.dataset.wunIntent) t = t.parentElement;
       if (!t || !t.dataset || !t.dataset.wunIntent) return;
       ev.preventDefault();
       var intent = t.dataset.wunIntent;
       var params = {};
       try { params = JSON.parse(t.dataset.wunParams || '{}'); } catch (e) {}
       dispatch(intent, params);
     }, false);
     window.WunWebFrame = { dispatch: dispatch };
   })();
   </script>")

(defn render-document
  "Wrap a single subtree's HTML in a complete document with the small
   stylesheet base-styles and the WebFrame bridge script. Used by the
   /web-frames/<key>/<token> endpoint."
  [missing tree]
  (str "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>WebFrame: " (escape (str missing)) "</title>"
       "<style>" base-styles "</style></head>"
       "<body>" (render-node tree) bridge-script "</body></html>"))
