# Add a screen with a form

## When to use this

The user asked for a new screen that takes input — a sign-up form,
an edit-profile page, a settings dialog. Anything where the screen
has both rendering AND state mutation triggered by user input.

## Inputs you need

- Screen keyword (e.g. `:myapp/sign-up`).
- Path (e.g. `/sign-up`).
- Field names + types (`{:email :string, :password :string}`).
- Submit intent name (e.g. `:myapp/submit-sign-up`).

## Steps

1. **Scaffold the screen file.** From the project root:

   ```bash
   wun add screen myapp/sign-up
   ```

   This creates `wun-shared/src/myapp/sign_up.cljc` with a starter
   `defscreen`.

2. **Scaffold the submit intent.**

   ```bash
   wun add intent myapp/submit-sign-up
   ```

   Creates `wun-shared/src/myapp/submit-sign-up_intent.cljc`. Edit
   the morph to update state. For a sign-up form:

   ```clojure
   (defintent :myapp/submit-sign-up
     {:params [:map [:email :string] [:password :string]]
      :morph  (fn [state {:keys [email]}]
                (assoc state :user {:email email}))})
   ```

3. **Add field-input intents** (one per editable field) so the form
   updates predicted state as the user types:

   ```clojure
   (defintent :myapp/set-email
     {:params [:map [:value :string]]
      :morph  (fn [state {:keys [value]}] (assoc state :draft-email value))})
   ```

4. **Render the form.** In the screen's `:render`, map your fields to
   `:wun/Input` with `:on-change` intent refs:

   ```clojure
   :render
   (fn [state]
     [:wun/Stack {:gap 12 :padding 24}
      [:wun/Heading {:level 1} "Sign up"]
      [:wun/Input {:value       (:draft-email state "")
                   :placeholder "Email"
                   :on-change   {:intent :myapp/set-email :params {}}}]
      [:wun/Button {:on-press {:intent :myapp/submit-sign-up
                               :params {:email    (:draft-email state)
                                        :password (:draft-password state)}}}
        "Create account"]])
   ```

5. **Wire side effects via fetch / persist** if the morph needs to
   call out to a database. Today, intents are pure morphs over server
   state. Persistent storage lives outside `:morph`; for the spike,
   keep it in `state/app-state` until phase 4 lands a real persistence
   contract.

6. **Require the new namespaces** from the server's entry namespace
   so `defcomponent`/`defscreen`/`defintent` side effects fire on
   load:

   ```clojure
   (ns wun.server.core
     (:require ...
               myapp.sign-up
               myapp.submit-sign-up-intent
               myapp.set-email-intent))
   ```

   Same in `wun-web/src/wun/web/core.cljs`.

## Verify

- `clojure -M -e "(require 'wun.server.core) (println :ok)"` — server loads.
- `npx shadow-cljs compile app` — web bundle builds.
- `wun dev` and visit the screen's path; type into the input, the
  draft state updates optimistically, submit fires the intent, the
  server-confirmed state arrives.
- `wun status` should show the screen's components are all green
  (otherwise some platform is missing a renderer; users on that
  platform will see a WebFrame fallback).
