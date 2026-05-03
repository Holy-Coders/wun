# Dogfood Wun (editable install via `wun link`)

## When to use this

The user is building one app while simultaneously developing the Wun
framework itself, and wants edits to Wun's source to flow into the app
without rebuilding or republishing — like `npm link` or `pip install -e`.

This is also the right setup when **you** (the agent) are asked to
modify Wun's behaviour while the user is in another project: linking
ensures every `wun add component` / `wun status` you run from the app
dir reads and writes the user's editable Wun checkout, not a stale
script-relative copy.

## How the link works

A single registry file at `~/.config/wun/active.edn` (overridable via
the `WUN_HOME` env var) records which Wun checkout is the editable
one. The CLI, the MCP server, generated apps' `deps.edn`, and `wun
doctor` all consult it so they agree.

```
{:root        "/Users/me/code/wun"
 :linked-at   1746230400
 :linked-from "/Users/me/code/wun"
 :version     1}
```

Resolution order (most specific wins):

1. `WUN_HOME` env var
2. `:root` from `~/.config/wun/active.edn`
3. The script's own checkout (bootstrap fallback)

`install.sh` writes this on first install, so a default install is
already linked.

## Steps

1. **Register the wun checkout.** From inside the wun repo:

   ```bash
   cd /path/to/wun
   wun link
   ```

   This:
   - writes `~/.config/wun/active.edn` with this checkout's absolute path
   - re-symlinks `bin/wun` into `/usr/local/bin` (or `~/.local/bin`)
   - prints the active path

   Re-running from a different checkout switches the active link.

2. **Link an app to the active wun.** From inside a generated app:

   ```bash
   cd /path/to/myapp
   wun link
   ```

   This rewrites each `wun/wun-{shared,server,web}` entry in
   `deps.edn` to `{:local/root "/abs/path/to/wun/wun-X"}` and saves a
   one-shot `.deps.edn.linkbak` so `wun unlink` can restore the
   original. Idempotent — running twice is a no-op.

   The output also prints the iOS / Android snippets to paste:

   ```
   iOS: in ios/Package.swift, set the wun .package(...) to:
         .package(path: "/abs/path/to/wun/wun-ios"),
   Android: in android/settings.gradle.kts, set:
         includeBuild("/abs/path/to/wun/wun-android")
   ```

   These aren't auto-edited because Swift / Kotlin DSL parsing is
   fragile. Copy them in by hand.

3. **Develop.** Edit Wun's source. The next `wun dev` (run from the
   app dir) picks up the changes — both the server JVM and shadow-cljs
   resolve through the linked `:local/root`.

4. **Unlink when shipping.** Either:

   ```bash
   wun unlink              # restores deps.edn from .deps.edn.linkbak
   ```

   …or, if no backup exists, `wun unlink` rewrites the deps to a
   `:git/url` form pinned at the active checkout's latest tag (you
   may want to fill in `:git/sha` after).

## One-shot scaffold

`wun new app myapp --link` scaffolds and links in one step (skips the
manual second `wun link` from inside the app).

## Verify

```bash
wun doctor
```

The output's "editable install" section reports:

- the active path and where it came from (env var vs registry)
- if you're inside an app: each `wun/*` `:local/root` resolved to its
  canonical path, with a drift warning if it doesn't match the active
  Wun

## Common mistakes

- **Two terminals on different checkouts.** The active link is
  global — only one Wun is "active" at a time, matching `npm link`
  semantics. `wun doctor` prints the active path so it's never a
  mystery; if you need per-app pinning, use absolute `:local/root`
  paths directly in `deps.edn` and skip `wun link`.
- **Forgetting the iOS/Android snippets.** Linking only rewrites
  `deps.edn`. Native paths in `Package.swift` and
  `settings.gradle.kts` need manual edits — the snippets are printed
  every time `wun link` succeeds.
- **`wun upgrade` on a linked checkout.** The auto-upgrade nudge is
  already suppressed when the working tree is dirty / non-master /
  ahead, which a linked dev checkout almost always is. Explicit `wun
  upgrade` still bails on a dirty tree — commit or stash first.
- **Stale `active.edn`.** If you delete the linked checkout, the
  resolver returns nil rather than a stale path. Re-run `wun link`
  from a valid checkout.
