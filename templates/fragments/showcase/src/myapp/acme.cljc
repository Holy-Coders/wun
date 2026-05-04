(ns myapp.acme
  "Acme Inc. — the 'fake SaaS' demo this app ships with.

   A small but complete end-to-end app rather than a feature
   checklist: real session-based login, a realtime dashboard whose
   numbers move on a server-side tick, an activity feed shared
   across all logged-in users, and a presence list of who else is
   online.

   This file is the cljc half (screens, intents, render fns). The
   server-only side (auth + sessions + realtime tick) lives in
   `myapp.server.acme`. To rip out the demo and start your real app,
   delete both files plus the `(myapp.server.acme/init!)` line in
   `server/main.clj` and the `myapp.acme` require in
   `web/main.cljs`."
  (:require [wun.intents :refer [defintent]]
            [wun.screens :refer [defscreen]]))

;; ---------------------------------------------------------------------------
;; Intents
;;
;; log-in / log-out are `:server-only? true` because their morphs
;; read cross-connection state (the users + sessions tables) that
;; only exists on the server. The clj branch delegates to
;; `myapp.server.acme` via `requiring-resolve` so this cljc doesn't
;; need a clj-only require (which would break the cljs build).

(defintent :myapp.acme/log-in
  {:server-only? true
   :params       [:map]
   :morph
   (fn [state _params]
     #?(:clj
        (let [vals    (get-in state [:forms :acme/login :values])
              do-auth (requiring-resolve 'myapp.server.acme/log-in!)
              result  (when do-auth (do-auth state vals))]
          (cond
            (:ok result)    (-> state
                                (assoc :myapp.acme/me (:ok result))
                                (assoc :session       {:token (:token result)})
                                (dissoc :myapp.acme/auth-error))
            (:error result) (assoc state :myapp.acme/auth-error (:error result))
            :else           state))
        :cljs state))})

(defintent :myapp.acme/log-out
  {:server-only? true
   :params       [:map]
   :morph
   (fn [state params]
     #?(:clj
        (do (when-let [f (requiring-resolve 'myapp.server.acme/log-out!)]
              (f state params))
            (-> state
                (dissoc :myapp.acme/me
                        :myapp.acme/dashboard
                        :myapp.acme/activity
                        :myapp.acme/online
                        :myapp.acme/auth-error
                        :session
                        :forms)))
        :cljs state))})

(defintent :myapp.acme/dismiss-error
  {:params [:map]
   :morph  (fn [state _] (dissoc state :myapp.acme/auth-error))})

;; ---------------------------------------------------------------------------
;; Login screen

(defn- demo-account-row [{:keys [user role]}]
  [:wun/Stack {:direction :row :gap 12 :padding 4}
   [:wun/Avatar  {:initials (subs user 0 1) :size 22}]
   [:wun/Text    {:variant :body} (str user " / " user)]
   [:wun/Badge   {:tone :info} role]])

(def ^:private demo-accounts
  [{:user "alice" :role "admin"}
   {:user "bob"   :role "manager"}
   {:user "carol" :role "viewer"}])

(defscreen :myapp.acme/login
  {:path   "/login"
   :meta   (fn [_] {:title       "Sign in · Acme"
                    :description "Acme — the demo SaaS this app ships with."})
   :render
   (fn [state]
     (let [err (:myapp.acme/auth-error state)]
       [:wun/Stack {:gap 24 :padding 32}
        [:wun/Heading {:level 1} "Acme Inc."]
        [:wun/Text    {:variant :body}
         "A pretend SaaS dashboard that ships with this app. Sign in "
         "with one of the demo accounts below to see realtime metrics, "
         "an activity feed, and a presence list."]

        [:wun/Card {:title "Demo accounts (password = username)"}
         (into [:wun/Stack {:gap 6 :padding 12}]
               (map demo-account-row demo-accounts))]

        [:wun/Card {:title "Sign in"}
         [:wun/Form {:id        :acme/login
                     :on-submit {:intent :myapp.acme/log-in :params {}}}
          [:wun/Stack {:gap 8 :padding 12}
           [:wun/Field {:form  :acme/login :name :username
                        :type  "text"      :label "Username"
                        :placeholder "alice"}]
           [:wun/Field {:form  :acme/login :name :password
                        :type  "password"  :label "Password"
                        :placeholder "alice"}]
           (when err
             [:wun/Stack {:direction :row :gap 8}
              [:wun/Badge  {:tone :danger} err]
              [:wun/Button {:on-press {:intent :myapp.acme/dismiss-error
                                       :params {}}}
               "Dismiss"]])
           [:wun/Button {:on-press {:intent :myapp.acme/log-in :params {}}}
            "Sign in"]]]]]))})

;; ---------------------------------------------------------------------------
;; Dashboard screen

(defn- format-money [n]
  (str "$" (clojure.string/replace
             (str (long (or n 0)))
             #"\B(?=(\d{3})+(?!\d))"
             ",")))

(defn- format-pct [pct] (str (or pct 0) "%"))

(defn- metric-card [title value sub]
  [:wun/Card {:title title}
   [:wun/Stack {:gap 4 :padding 12}
    [:wun/Heading {:level 2} value]
    [:wun/Text    {:variant :body} sub]]])

(defn- activity-row [{:keys [who event tone]}]
  [:wun/Stack {:direction :row :gap 8 :padding 4}
   [:wun/Badge {:tone (or tone :info)} (or who "—")]
   [:wun/Text  {:variant :body}        (or event "")]])

(defn- online-row [{:keys [username]} me-name]
  [:wun/Stack {:direction :row :gap 8 :padding 4}
   [:wun/Avatar {:initials (some-> username (subs 0 1)) :size 20}]
   [:wun/Text   {:variant :body}
    (str (or username "—")
         (when (= username me-name) "  (you)"))]])

(defscreen :myapp.acme/dashboard
  {:path   "/dashboard"
   :meta   (fn [state]
             {:title (str "Dashboard · "
                          (get-in state [:myapp.acme/me :username] "Acme"))})
   :render
   (fn [state]
     (let [{:keys [username role]} (:myapp.acme/me state)
           {:keys [mrr signups-today conversion-pct sparkline]}
           (:myapp.acme/dashboard state)
           feed    (:myapp.acme/activity state [])
           online  (:myapp.acme/online state [])
           me-name username]
       [:wun/Stack {:gap 16 :padding 24}
        [:wun/Stack {:direction :row :gap 16 :padding 4}
         [:wun/Heading {:level 1} "Acme · Dashboard"]
         [:wun/Spacer  {:size 8}]
         [:wun/Avatar  {:initials (some-> username (subs 0 1))
                        :size 24}]
         [:wun/Text    {:variant :body} (or username "—")]
         [:wun/Badge   {:tone :info}    (or role "—")]
         [:wun/Button  {:on-press {:intent :myapp.acme/log-out :params {}}}
          "Sign out"]]

        [:wun/Divider {:thickness 1}]

        [:wun/Stack {:direction :row :gap 12}
         (metric-card "MRR"            (format-money mrr)
                      "monthly recurring · live")
         (metric-card "Signups today"  (str (or signups-today 0))
                      "since 00:00 UTC")
         (metric-card "Conversion"     (format-pct conversion-pct)
                      "trial → paid")
         (metric-card "Sparkline (1h)"
                      (if (seq sparkline)
                        (str (apply max sparkline) " peak")
                        "—")
                      (str (count sparkline) " samples"))]

        [:wun/Stack {:direction :row :gap 12}
         [:wun/Card {:title (str "Activity feed (" (count feed) ")")}
          (if (seq feed)
            (into [:wun/Stack {:gap 4 :padding 8}]
                  (map activity-row feed))
            [:wun/Text {:variant :body}
             "Quiet so far. The tick will fire shortly."])]

         [:wun/Card {:title (str "Online (" (count online) ")")}
          (if (seq online)
            (into [:wun/Stack {:gap 4 :padding 8}]
                  (map #(online-row % me-name) online))
            [:wun/Text {:variant :body} "Just you."])]]]))})
