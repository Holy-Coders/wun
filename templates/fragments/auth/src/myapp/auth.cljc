(ns myapp.auth
  "Cookie-less, token-based session auth -- minimal but real. The
   auth intents are :server-only? since password verification can't
   run on the client (it would need the users table).

   The token is held in `(:session state)`. The web client's existing
   hot-cache persists state to localStorage between reloads, so the
   token survives a page refresh. SSE connection-level auth is NOT
   wired up here -- the SSE stream is open to anyone today; intents
   that mutate user-owned data check `(:session state)` themselves."
  (:require [wun.intents :refer [definent]]
            [wun.screens :refer [defscreen]]
            #?(:clj [myapp.server.auth :as auth])))

(definent :myapp/sign-up
  {:server-only? true
   :params       [:map [:email [:string {:min 3}]]
                       [:password [:string {:min 8}]]]
   :morph
   (fn [state {:keys [email password]}]
     #?(:clj
        (try
          (let [{:keys [user-id token]} (auth/sign-up! email password)]
            (assoc state :session {:user-id user-id :token token :email email}
                         :auth-error nil))
          (catch Exception e
            (assoc state :auth-error (.getMessage e))))
        :cljs
        state))})

(definent :myapp/log-in
  {:server-only? true
   :params       [:map [:email :string] [:password :string]]
   :morph
   (fn [state {:keys [email password]}]
     #?(:clj
        (if-let [{:keys [user-id token]} (auth/log-in! email password)]
          (assoc state :session {:user-id user-id :token token :email email}
                       :auth-error nil)
          (assoc state :auth-error "invalid credentials"))
        :cljs
        state))})

(definent :myapp/log-out
  {:server-only? true
   :params       [:map]
   :morph
   (fn [state _]
     #?(:clj
        (do (when-let [token (get-in state [:session :token])]
              (auth/log-out! token))
            (dissoc state :session :auth-error))
        :cljs
        (dissoc state :session :auth-error)))})

(defscreen :myapp/signup
  {:path "/signup"
   :render
   (fn [state]
     [:wun/Stack {:gap 16 :padding 24}
      [:wun/Heading {:level 1} "Sign up"]
      (when-let [err (:auth-error state)]
        [:wun/Text {:variant :body :color :error} (str "Error: " err)])
      [:wun/Form {:on-submit {:intent :myapp/sign-up
                              :params {:email    :form/email
                                       :password :form/password}}}
       [:wun/Stack {:gap 8}
        [:wun/TextField {:name "email"    :placeholder "you@example.com"}]
        [:wun/TextField {:name "password" :placeholder "min 8 chars"
                         :type :password}]
        [:wun/Button   {:type :submit} "Create account"]]]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :wun/navigate :params {:path "/login"}}}
        "Already have an account? Log in"]]])})

(defscreen :myapp/login
  {:path "/login"
   :render
   (fn [state]
     [:wun/Stack {:gap 16 :padding 24}
      [:wun/Heading {:level 1} "Log in"]
      (when-let [err (:auth-error state)]
        [:wun/Text {:variant :body :color :error} (str "Error: " err)])
      [:wun/Form {:on-submit {:intent :myapp/log-in
                              :params {:email    :form/email
                                       :password :form/password}}}
       [:wun/Stack {:gap 8}
        [:wun/TextField {:name "email"}]
        [:wun/TextField {:name "password" :type :password}]
        [:wun/Button   {:type :submit} "Log in"]]]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :wun/navigate :params {:path "/signup"}}}
        "Create an account"]]])})
