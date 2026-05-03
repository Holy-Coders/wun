# Working on Wun (for coding agents)

This file orients Claude / Cursor / Aider / any LLM-driven coding agent
that lands inside the Wun monorepo. Read it before making changes.

## What Wun is

A **Clojure-first cross-platform server-driven UI framework**.

- The **server** is the source of truth for UI state.
- It emits intent-shaped UI trees as **patches over a single SSE
  channel** per client.
- **Clients** mirror the tree, render natively (SwiftUI / Compose /
  reagent), dispatch user actions back as data-shaped intents.
- **Intents** are defined once in `.cljc` with a shared `morph` fn
  that runs on both server (authoritative) and client (optimistic
  prediction).
- **Components** are a namespaced vocabulary defined as data; each
  platform binds keywords to native renderers; unsupported components
  fall back to WebView frames seamlessly via Hotwire Native.

If you're confused about architecture, read `docs/architecture/` and
the brief at the top of `wun-server/src/wun/server/http.clj`.

## Repo layout

| dir                    | what it is                                          |
|------------------------|-----------------------------------------------------|
| `wun-shared/`          | cljc registries used by both server and web         |
| `wun-server/`          | Pedestal SSE + intent endpoint (Clojure)            |
| `wun-web/`             | reagent + shadow-cljs web client                    |
| `wun-ios/`             | SwiftUI client, SwiftPM package                     |
| `wun-android/`         | Compose Multiplatform Desktop client (Kotlin/JVM)   |
| `wun-{ios,android}-example/` | reference user-component packs                |
| `templates/`           | scaffolds consumed by `wun new`                     |
| `cli/`, `bin/`         | the `wun` developer CLI (babashka)                  |
| `migrations/`          | codemod scripts for breaking-change cleanup         |
| `docs/architecture/`   | mapping to LiveView / Hotwire concepts              |
| `skills/`              | canonical Wun task templates for AI agents          |
| `mcp/`                 | MCP server exposing wun tools to LLM clients        |

## The three macros

Everything in Wun flows through three open registries declared with
side-effecting macros:

- **`defcomponent`** in `wun-shared/src/wun/components.cljc`
  — registers a component keyword (`:wun/Stack`, `:myapp/Card`) with
  its schema, fallback policy, and per-platform native names.

- **`defscreen`** in `wun-shared/src/wun/screens.cljc`
  — registers a screen keyword with `:path`, `:render` (state → tree),
  optional `:meta` (state → page metadata), optional `:present`
  (`:push` or `:modal`).

- **`defintent`** in `wun-shared/src/wun/intents.cljc`
  — registers an intent keyword with a Malli `:params` schema and a
  `:morph` fn `(state, params) → state` that runs identically on
  server and client.

**Framework code uses these same APIs as user code. There is no
privileged path.** `:wun/Stack` and `:myapp/RichEditor` are
indistinguishable to the runtime. Treat them the same.

## Don't write boilerplate by hand

The CLI generators are idempotent and produce all the cross-platform
plumbing. Use them:

```bash
wun add component myapp/Card     # scaffolds 5 files (cljc + iOS + Android + registries)
wun add screen    myapp/profile  # scaffolds a screen .cljc
wun add intent    myapp/log-in   # scaffolds an intent .cljc
```

Hand-writing these is a smell. If you find yourself adding a renderer
in five places at once, you wanted `wun add component` instead.

## Common tasks (point you at the relevant skill)

| task                                | skill                                          |
|-------------------------------------|------------------------------------------------|
| Add a new screen with a form        | `skills/add-screen-with-form.md`               |
| Wire a new intent end-to-end        | `skills/wire-an-intent.md`                     |
| Ship a custom component pack        | `skills/add-component-pack.md`                 |
| Land a breaking framework change    | `skills/ship-a-breaking-change.md`             |

## Things that look obvious but aren't

- **Capability negotiation**: clients advertise `?caps=...` on
  connect; server collapses unsupported subtrees to
  `[:wun/WebFrame {:src ...}]` *at the smallest containing level*.
  When you add a new client renderer, also bump its `:since` in
  `defcomponent` if the schema changed.
- **Optimistic morphs**: the same `:morph` fn runs server-side
  (authoritative) and client-side (predictive). Pure functions only.
  Side effects belong in `:fetch` (server-only) or under server
  endpoints, not in `:morph`.
- **Server-side dedup**: intents are dedup'd by `:id` (LRU 1024).
  Client retries on reconnect are safe -- don't add another dedup
  layer on top.
- **Screen-stack is per connection**: `:wun/navigate` / `:wun/pop`
  / `:wun/replace` route to the originating connection's stack via
  `:conn-id`. Other clients are unaffected.

## Things that are easy to get wrong

- Clojure ns paths vs. Kotlin/Swift class names: `wun new app
  myapp` validates the name against `[a-z][a-z0-9]*`. Hyphenated
  names break Kotlin packages; if the user wants one, push back and
  ask for an unhyphenated form instead.
- `:local/root` vs `:git/url` deps: templates use `:local/root` to a
  sibling `wun/` clone. Don't change to remote refs unless the user
  explicitly asks.
- Wire format changes are observable across all four platforms.
  Touching `wun.server.wire/patch-envelope`, the iOS `Envelope`
  struct, or the Kotlin `Envelope` data class requires updating all
  three in the same change.

## How to verify you didn't break anything

```bash
# from repo root
clojure -M -e "(require 'wun.server.core) (println :ok)"  # server
cd wun-web         && npx shadow-cljs compile app          # web
cd wun-ios-example && swift build                          # iOS
cd wun-android     && gradle compileKotlin                 # Android
```

`wun status` shows per-platform component coverage at a glance.

## What NOT to commit

- Never modify `wun-server/resources/logback.xml` to silence specific
  warnings without checking with the user first.
- Don't touch `cli/wun.bb`'s ANSI escape map -- the bytes need
  `bb -e` rewriting (literal escapes, not ``).
- Don't add Clojars / Maven Central publishing infrastructure
  without explicit ask. Wun ships via Git tags + JitPack today.

## When in doubt

Read `docs/architecture/head-and-cache.md` for the LiveView/Hotwire
mental model; read `wun-server/src/wun/server/http.clj` for the wire
contract. Both are deliberately heavily commented.
