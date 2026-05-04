(ns wun.app.acme
  "Acme Inc. — the framework's built-in 'fake SaaS' demo. A working
   end-to-end app rather than a feature checklist: real session-based
   login, a realtime dashboard whose numbers move on a server-side
   tick, an activity feed shared across all logged-in users, and a
   presence list of who else is online.

   This file is the user-namespace (cljc) half: screens, intents,
   render fns. The server-only half (auth, sessions, the tick that
   drives realtime, telemetry sink) lives in
   `wun.server.app.acme`. Mirrors
   `templates/fragments/showcase/src/myapp/acme.cljc` verbatim except
   for `:wun.app.acme/*` keys instead of `:myapp.acme/*`, so the
   framework's own `wun dev` loop can exercise the same demo.

   Auth is real PBKDF2-SHA256 with per-user salts; the demo passwords
   match the usernames so the login screen can show them in plain
   text. See `wun.server.app.acme/seed-users` to rotate."
  (:require [wun.intents :refer [defintent]]
            [wun.screens :refer [defscreen]]))

;; ---------------------------------------------------------------------------
;; Intents
;;
;; log-in / log-out are `:server-only? true` because their morphs read
;; cross-connection state (the users + sessions tables) that only
;; exists on the server. The clj branch delegates to
;; `wun.server.app.acme` via `requiring-resolve` so this cljc doesn't
;; need a clj-only require (which would break the cljs build).

(defintent :wun.app.acme/log-in
  {:server-only? true
   :params       [:map]
   :morph
   (fn [state _params]
     #?(:clj
        (let [vals    (get-in state [:forms :acme/login :values])
              do-auth (requiring-resolve 'wun.server.app.acme/log-in!)
              result  (when do-auth (do-auth state vals))]
          (cond
            (:ok result)    (-> state
                                (assoc :wun.app.acme/me (:ok result))
                                (assoc :session         {:token (:token result)})
                                (dissoc :wun.app.acme/auth-error))
            (:error result) (assoc state :wun.app.acme/auth-error (:error result))
            :else           state))
        :cljs state))})

(defintent :wun.app.acme/log-out
  {:server-only? true
   :params       [:map]
   :morph
   (fn [state params]
     #?(:clj
        (do (when-let [f (requiring-resolve 'wun.server.app.acme/log-out!)]
              (f state params))
            (-> state
                (dissoc :wun.app.acme/me
                        :wun.app.acme/dashboard
                        :wun.app.acme/activity
                        :wun.app.acme/online
                        :wun.app.acme/auth-error
                        :session
                        :forms)))
        :cljs state))})

(defintent :wun.app.acme/dismiss-error
  {:params [:map]
   :morph  (fn [state _] (dissoc state :wun.app.acme/auth-error))})

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

(defscreen :wun.app.acme/login
  {:path   "/login"
   :meta   (fn [_] {:title       "Sign in · Acme"
                    :description "Acme — the Wun built-in SaaS demo."})
   :render
   (fn [state]
     (let [err (:wun.app.acme/auth-error state)]
       [:wun/Stack {:gap 24 :padding 32}
        [:wun/Heading {:level 1} "Acme Inc."]
        [:wun/Text    {:variant :body}
         "A pretend SaaS dashboard that ships with every Wun install. "
         "Sign in with one of the demo accounts below to see realtime "
         "metrics, an activity feed, and a presence list."]

        [:wun/Card {:title "Demo accounts (password = username)"}
         (into [:wun/Stack {:gap 6 :padding 12}]
               (map demo-account-row demo-accounts))]

        [:wun/Card {:title "Sign in"}
         [:wun/Form {:id        :acme/login
                     :on-submit {:intent :wun.app.acme/log-in :params {}}}
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
              [:wun/Button {:on-press {:intent :wun.app.acme/dismiss-error
                                       :params {}}}
               "Dismiss"]])
           [:wun/Button {:on-press {:intent :wun.app.acme/log-in :params {}}}
            "Sign in"]]]]]))})

;; ---------------------------------------------------------------------------
;; Dashboard screen

(defn- format-money [n]
  (str "$" (clojure.string/replace
             (str (long (or n 0)))
             #"\B(?=(\d{3})+(?!\d))"
             ",")))

(defn- format-pct [pct]
  (str (or pct 0) "%"))

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

(defscreen :wun.app.acme/dashboard
  {:path   "/dashboard"
   :meta   (fn [state]
             {:title (str "Dashboard · "
                          (get-in state [:wun.app.acme/me :username] "Acme"))})
   :render
   (fn [state]
     (let [{:keys [username role]} (:wun.app.acme/me state)
           {:keys [mrr signups-today conversion-pct sparkline]}
           (:wun.app.acme/dashboard state)
           feed    (:wun.app.acme/activity state [])
           online  (:wun.app.acme/online state [])
           me-name username]
       [:wun/Stack {:gap 16 :padding 24}
        ;; Header
        [:wun/Stack {:direction :row :gap 16 :padding 4}
         [:wun/Heading {:level 1} "Acme · Dashboard"]
         [:wun/Spacer  {:size 8}]
         [:wun/Avatar  {:initials (some-> username (subs 0 1))
                        :size 24}]
         [:wun/Text    {:variant :body} (or username "—")]
         [:wun/Badge   {:tone :info}    (or role "—")]
         [:wun/Button  {:on-press {:intent :wun.app.acme/log-out :params {}}}
          "Sign out"]]

        [:wun/Divider {:thickness 1}]

        ;; Metric grid
        [:wun/Stack {:direction :row :gap 12}
         (metric-card "MRR"             (format-money mrr)
                      "monthly recurring · live")
         (metric-card "Signups today"   (str (or signups-today 0))
                      "since 00:00 UTC")
         (metric-card "Conversion"      (format-pct conversion-pct)
                      "trial → paid")
         (metric-card "Sparkline (1h)"
                      (if (seq sparkline)
                        (str (apply max sparkline) " peak")
                        "—")
                      (str (count sparkline) " samples"))]

        ;; Two-column lower row: activity + presence
        [:wun/Stack {:direction :row :gap 12}
         [:wun/Card {:title (str "Activity feed (" (count feed) ")")}
          (if (seq feed)
            (into [:wun/Stack {:gap 4 :padding 8}]
                  (map activity-row feed))
            [:wun/Text {:variant :body} "Quiet so far. The tick will fire shortly."])]

         [:wun/Card {:title (str "Online (" (count online) ")")}
          (if (seq online)
            (into [:wun/Stack {:gap 4 :padding 8}]
                  (map #(online-row % me-name) online))
            [:wun/Text {:variant :body} "Just you."])]]]))})
