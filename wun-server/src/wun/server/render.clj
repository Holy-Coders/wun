(ns wun.server.render
  "Phase 0 hardcoded screen. In phase 1 this becomes `defscreen` plus a
   render pipeline driven by the interceptor chain.")

(defn counter-screen
  "Pure (state) -> hiccup-shaped tree using the foundational vocabulary."
  [state]
  [:wun/Stack {:gap 12 :padding 24}
   [:wun/Text {:variant :h1} (str "Counter: " (:counter state 0))]
   [:wun/Stack {:direction :row :gap 8}
    [:wun/Button {:on-press {:intent :counter/dec :params {}}} "-"]
    [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]
    [:wun/Button {:on-press {:intent :counter/reset :params {}}} "reset"]]
   [:wun/Text {:variant :body}
    "Phase 0 spike. The server is the source of truth; this tree is re-emitted in full on every intent."]])
