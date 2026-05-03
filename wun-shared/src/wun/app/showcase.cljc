(ns wun.app.showcase
  "Built-in showcase demo, mounted at `/showcase` when the framework's
   own dev loop is running (`wun dev` from the repo root). Sister of
   `wun.app.counter` / `wun.app.about` — these `wun.app.*` namespaces
   are the dogfood that proves the framework boots end-to-end without
   any consumer app present.

   The user-facing template version of this lives at
   `templates/fragments/showcase/src/myapp/showcase.cljc`. The two
   are kept structurally identical; only namespaces and component
   keys differ. Tweak both together when adding a new demo."
  (:require [wun.components :refer [defcomponent]]
            [wun.screens    :refer [defscreen]]
            [wun.intents    :refer [defintent]]
            [wun.theme      :as theme]))

(defcomponent :wun.app.showcase/RichEditor
  {:since    1
   :schema   [:map [:html {:optional true} :string]]
   :loading  :inherit
   :fallback :web
   :ios      "RichEditor"
   :android  "RichEditor"})

(def ^:private demos
  [{:slug "forms"     :title "Forms + uploads"
    :blurb "A small form with text fields and a file picker."}
   {:slug "live"      :title "Live state"
    :blurb "Shared counter + presence, all wired through pubsub."}
   {:slug "fallback"  :title "Capability fallback"
    :blurb "A custom component that renders natively on web and falls back to a WebFrame elsewhere."}
   {:slug "theme"     :title "Theme tokens"
    :blurb "Server-cascaded theme tokens resolved into the tree."}])

(defn- hub-card [{:keys [slug title blurb]}]
  [:wun/Card {:title title}
   [:wun/Stack {:gap 6 :padding 12}
    [:wun/Text {:variant :body} blurb]
    [:wun/Link {:href     (str "/showcase/" slug)
                :on-press {:intent :wun/navigate
                           :params {:path (str "/showcase/" slug)}}}
     "Open →"]]])

(defscreen :wun.app.showcase/hub
  {:path   "/showcase"
   :meta   (fn [_] {:title "Showcase"})
   :render (fn [_state]
             [:wun/Stack {:gap 16 :padding 24}
              [:wun/Heading {:level 1} "Showcase"]
              [:wun/Text {:variant :body}
               "Each card is a small demo of one part of the framework."]
              (into [:wun/Stack {:gap 12}]
                    (map hub-card demos))])})

;; ---------------------------------------------------------------------------
;; Forms + uploads

(defscreen :wun.app.showcase/forms
  {:path   "/showcase/forms"
   :meta   (fn [_] {:title "Forms"})
   :render (fn [state]
             (let [last (:wun.app.showcase/form-last state)]
               [:wun/Stack {:gap 16 :padding 24}
                [:wun/Heading {:level 1} "Forms + uploads"]
                [:wun/Form {:id        :showcase/contact
                            :on-submit {:intent :wun.app.showcase/submit-form}}
                 [:wun/Stack {:gap 8}
                  [:wun/Field    {:form :showcase/contact :name :name
                                  :type "text" :label "Name"
                                  :placeholder "Aaron"}]
                  [:wun/Field    {:form :showcase/contact :name :email
                                  :type "email" :label "Email"
                                  :placeholder "aaron@example.com"}]
                  [:wun/FileInput {:form  :showcase/contact :field :avatar
                                   :accept "image/*"}]
                  [:wun/Button {:on-press {:intent :wun.app.showcase/submit-form}}
                   "Submit"]]]
                (when last
                  [:wun/Card {:title "Last submission"}
                   [:wun/Text {:variant :body} (pr-str last)]])]))})

(defintent :wun.app.showcase/submit-form
  {:params [:map]
   :morph  (fn [state {:keys [values]}]
             (assoc state :wun.app.showcase/form-last (or values {})))})

;; ---------------------------------------------------------------------------
;; Live state (pubsub + presence)

(defscreen :wun.app.showcase/live
  {:path   "/showcase/live"
   :meta   (fn [_] {:title "Live"})
   :render (fn [state]
             (let [n       (get-in state [:wun.app.showcase/live :count] 0)
                   present (get-in state [:wun.app.showcase/live :present] [])]
               [:wun/Stack {:gap 16 :padding 24}
                [:wun/Heading {:level 1} "Live state"]
                [:wun/Text {:variant :body}
                 "Open this page in two windows — both reflect the same counter
                  and a live presence list, all routed through the in-process
                  pubsub bus."]
                [:wun/Card {:title "Shared counter"}
                 [:wun/Stack {:gap 8 :padding 12 :direction :row}
                  [:wun/Heading {:level 2} (str n)]
                  [:wun/Button {:on-press {:intent :wun.app.showcase/live-bump}}
                   "Bump"]]]
                [:wun/Card {:title (str "Present (" (count present) ")")}
                 (if (empty? present)
                   [:wun/Text {:variant :body} "Just you, for now."]
                   (into [:wun/Stack {:gap 4 :padding 8}]
                         (map (fn [conn-id]
                                [:wun/Text {:variant :body} (str conn-id)])
                              present)))]]))})

(defintent :wun.app.showcase/live-bump
  {:params [:map]
   :morph  (fn [state _]
             (update-in state [:wun.app.showcase/live :count] (fnil inc 0)))})

;; ---------------------------------------------------------------------------
;; Capability fallback

(defscreen :wun.app.showcase/fallback
  {:path   "/showcase/fallback"
   :meta   (fn [_] {:title "Fallback"})
   :render (fn [_state]
             [:wun/Stack {:gap 16 :padding 24}
              [:wun/Heading {:level 1} "Capability fallback"]
              [:wun/Text {:variant :body}
               "The component below is `:wun.app.showcase/RichEditor`. Web
                binds it to a styled DOM; iOS and Android haven't registered
                a native renderer, so the framework collapses just this
                subtree to a `:wun/WebFrame` at the smallest containing
                level."]
              [:wun.app.showcase/RichEditor
               {:html "Hello. This block is a custom component the iOS /
                       Android clients don't know about — they'll see a
                       sandboxed WebView instead."}]])})

;; ---------------------------------------------------------------------------
;; Theme tokens

(defscreen :wun.app.showcase/theme
  {:path   "/showcase/theme"
   :meta   (fn [_] {:title "Theme"})
   :render (fn [_state]
             (let [tokens (theme/default-theme)]
               [:wun/Stack {:gap 16 :padding 24}
                [:wun/Heading {:level 1} "Theme tokens"]
                [:wun/Text {:variant :body}
                 "Tokens cascade server → client on every patch envelope. The
                  list below is the resolved snapshot for this connection."]
                (if (empty? tokens)
                  [:wun/Text {:variant :body}
                   "No tokens registered yet — call `(wun.theme/set-default! …)`
                    in your server init."]
                  (into [:wun/Stack {:gap 4}]
                        (for [[k v] (sort-by (comp str key) tokens)]
                          [:wun/Stack {:direction :row :gap 12 :padding 6}
                           [:wun/Badge {:tone :info} (str k)]
                           [:wun/Text  {:variant :body} (str v)]])))]))})
