---
title: CLI
description: Every wun subcommand at a glance.
---

> **TL;DR.** One command, several subcommands. `wun doctor` checks
> your toolchain. `wun new app` scaffolds a project. `wun add
> component / screen / intent` splices cross-platform plumbing
> idempotently. `wun dev` runs server + web watch together.

The `wun` CLI is a single-file babashka script (`cli/wun.bb`), invoked
via the `wun` shell wrapper installed by `install.sh`. All commands
walk up from `$PWD` looking for `wun-server/deps.edn`, so they work
from anywhere inside a wun monorepo.

## doctor

```bash
wun doctor
```

Verifies java / clojure / node / swift / gradle / bb are on PATH and
the monorepo layout is intact. Prints versions and a green check per
tool.

## status

```bash
wun status
```

Prints a per-component coverage matrix:

```
component               web  ios  android  server-html
:wun/Stack               ✓    ✓    ✓        ✓
:wun/Avatar              ◌    ✓    ✓        ✓
:myapp/Greeting          ◌    ✓    ✓        ·
```

`✓` native renderer · `◌` WebFrame fallback · `·` server default HTML.

## dev

```bash
wun dev
```

Starts the Clojure server on `:8080` and `shadow-cljs watch` on
`:8081` together. One Ctrl-C stops both. Pre-flight check refuses to
start if either port is taken (and identifies the listening PID).
Auto-runs `npm install` in `wun-web/` if `node_modules` is missing.

## run \<target\>

```bash
wun run server     # clojure -M -m wun.server.core
wun run web        # npx shadow-cljs watch app
wun run ios        # swift run wun-demo-mac
wun run android    # gradle run (Compose Desktop)
```

## add component / screen / intent

```bash
wun add component myapp/Card     # cljc decl + iOS + Android + registry splices
wun add screen    myapp/profile  # screen .cljc with starter render fn
wun add intent    myapp/log-in   # intent .cljc with Malli schema + morph stub
```

All generators are **idempotent** — re-running detects existing
files and skips them with a hint. Names must match `[a-z][a-z0-9]*`
(no hyphens; Kotlin-package-safe).

## new app / new pack

```bash
wun new app  myapp               # standalone Wun project (server + web + ios + android)
wun new pack myapp-components    # reusable :myapp/* component pack
```

`new app` scaffolds against a sibling `../wun/` clone via
`:local/root` deps. The generated project has its own `deps.edn`,
`shadow-cljs.edn`, `package.json`, `Package.swift`, and Gradle module.

## upgrade

```bash
wun upgrade
```

Fetches origin/master, prints incoming commits with `BREAKING:` lines
in red, lists any new files under `migrations/`, then prompts to
fast-forward. Refuses on a dirty tree. Runs `npm install` afterward
if `package.json` changed.

Auto-check runs every 24h on any `wun <cmd>`; in a TTY you'll be
prompted to upgrade if you're behind. Set `WUN_NO_AUTO_UPGRADE=1` to
silence.

## migrations

```bash
wun migrations list
wun migrations apply <id> [--dir <path>]
```

Lists / applies codemod scripts under `migrations/`. Each script is a
babashka file taking one argument (target dir) and rewriting files in
place. Idempotent.

## release

```bash
wun release v0.1.0
```

Validates the version, refuses on a dirty tree or non-master branch,
then `git tag -a v0.1.0 -m "Release v0.1.0"` and `git push origin
master --tags`. Prints copy-paste consumer coordinates for Clojure /
Swift / Android.

## help

```bash
wun help
```

One-screen summary of every subcommand.

## Read next

- [Your first app](/getting-started/your-first-app/) — `wun new app`
  walked end-to-end.
- [Migrations](/reference/migrations/) — when to ship a codemod
  versus a CHANGELOG note.
- [MCP server](/ai/mcp/) — the same scaffolders exposed to LLM
  clients via Model Context Protocol.
