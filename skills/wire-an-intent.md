# Wire an intent end-to-end

## When to use this

The user described a discrete state transition the app needs to
support — "increment counter", "mark todo done", "log out". Every
state mutation in Wun goes through an intent.

## Inputs you need

- Intent keyword (e.g. `:myapp/mark-done`).
- Param schema (Malli) — what the caller must pass.
- Morph behaviour — how `(state, params)` maps to new state.
- Whether the intent has a UI trigger (button, switch, link).

## Steps

1. **Scaffold the intent file.**

   ```bash
   wun add intent myapp/mark-done
   ```

2. **Define the schema and morph.** Open the generated cljc and
   replace the placeholder:

   ```clojure
   (defintent :myapp/mark-done
     {:params [:map [:id :uuid]]
      :morph  (fn [state {:keys [id]}]
                (update state :todos
                        (fn [ts] (mapv #(if (= (:id %) id)
                                          (assoc % :done? true) %)
                                       ts))))})
   ```

   Rules:
   - The morph is **pure**. Same input → same output.
   - The schema is **Malli**. Validation runs on both server and
     client; bad params are dropped client-side and 400'd server-side.
   - No I/O in the morph. Side effects belong in a separate `:fetch`
     (server-only) field if a screen needs to load remote data.

3. **Wire the UI trigger.** Edit the screen that should fire it and
   pass the intent ref to a button (or input or link):

   ```clojure
   [:wun/Button {:on-press {:intent :myapp/mark-done
                            :params {:id (:id todo)}}}
     "Mark done"]
   ```

4. **Require the namespace** from `wun.server.core` (and
   `wun.web.core` if web should also predict it locally).

## Verify

- POST the intent against a running dev server:

  ```bash
  curl -X POST -H 'Content-Type: application/json' \
    -d '{"intent":"myapp/mark-done","params":{"id":"<uuid>"},"id":"smoke-1"}' \
    http://localhost:8080/intent
  ```

- The server logs `dedup'd intent id=smoke-1` if you re-fire with the
  same id, confirming the idempotency cache works.
- The next SSE envelope contains the morph'd state.
- Optimistic prediction: in the web client, the UI updates *before*
  the server confirms; if the morph diverges from server-side, the
  view converges on the next confirmed envelope.

## Common mistakes

- **Putting side effects in the morph.** It runs twice (server +
  client) per dispatch; side effects double-fire. Move them out.
- **Returning a different shape from the morph.** State should stay
  shape-stable; new fields are fine, but don't change existing
  shapes without a migration.
- **Forgetting the param schema.** A missing schema means every
  payload is accepted; debugging gets nasty fast. Always Malli-spec
  the params, even loosely (`:map` is fine for a stub).
