# Ship a breaking change

## When to use this

The change you're about to make breaks the public API:
- a component prop rename (`:on-press` → `:on-tap`)
- an intent schema change
- a namespace move
- a wire-format key rename

Anything where existing consumer code stops working without
mechanical adjustment.

## Inputs you need

- The exact old → new transformation.
- Whether the codemod is regex-safe or needs `rewrite-clj`.

## Steps

1. **Land the framework change.** Edit the relevant files.

2. **Add a migration script.** Copy `migrations/0000-template.bb`
   to a numbered slug:

   ```bash
   cp migrations/0000-template.bb migrations/0001-rename-on-press.bb
   chmod +x migrations/0001-rename-on-press.bb
   ```

   Implement the codemod:

   ```clojure
   (defn- transform [content]
     (-> content
         (str/replace ":on-press" ":on-tap")
         (str/replace "\"on-press\"" "\"on-tap\"")))
   ```

   The migration must be **idempotent** -- running it twice is
   safe. Rule of thumb: substitute only specific occurrences,
   never patterns that could re-match the result.

3. **Tag the commit subject with `BREAKING:`.** The auto-upgrade
   prompt (`wun upgrade`) highlights `BREAKING:` lines in red and
   surfaces any new files under `migrations/`:

   ```
   BREAKING: rename :on-press to :on-tap on :wun/Button
   ```

4. **Document the change** in the commit body and (if the API
   surface is documented) in `docs/`.

## Verify

- `wun migrations list` shows the new file.
- `wun migrations apply 0001 --dir <a-test-app>` rewrites the
  test app and the result still builds.
- Re-running the same `wun migrations apply 0001 --dir <a-test-app>`
  is a no-op (idempotency check).
- `wun upgrade` from a downstream consumer correctly highlights
  the BREAKING line and points at `migrations/0001-*.bb`.

## What NOT to do

- **Don't** auto-apply migrations on `wun upgrade`. Users review
  the diff first; codemods rewrite source files and the user owns
  that decision.
- **Don't** ship a migration without bumping the appropriate
  `:since` in the touched `defcomponent`. Capability negotiation
  uses it to ship WebFrame fallbacks to outdated clients.
- **Don't** combine unrelated breaking changes in one migration.
  One breaking change per migration script -- consumers can adopt
  them at their own pace.
