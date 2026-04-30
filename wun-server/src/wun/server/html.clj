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
  (:require [clojure.string :as str]))

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

(defmethod render-component :wun/Button [_ {:keys [on-press]} children]
  (let [intent (some-> on-press :intent str)]
    (str "<button class='wun-button' "
         "title='Intent: " (escape (or intent "(none)")) "' "
         "onclick='alert(\"Buttons inside a WebFrame can&#39;t fire native intents yet.\")'>"
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
       ".wun-unknown{color:crimson;font-family:ui-monospace,monospace;}"))

(defn render-document
  "Wrap a single subtree's HTML in a complete document with the small
   stylesheet base-styles. Used by the /web-frames/<key>/<token>
   endpoint."
  [missing tree]
  (str "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>WebFrame: " (escape (str missing)) "</title>"
       "<style>" base-styles "</style></head>"
       "<body>" (render-node tree) "</body></html>"))
