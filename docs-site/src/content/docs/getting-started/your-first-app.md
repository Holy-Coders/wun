---
title: Your first app
description: Scaffold, run, and extend a Wun app.
---

A new Wun app is a sibling of the wun monorepo — its `deps.edn`
references the framework via `:local/root` paths to `../wun/wun-*`
by default, so the dev loop works without publishing.

## Scaffold

```bash
cd ~/code
git clone https://github.com/Holy-Coders/wun.git    # the framework
wun new app myapp                                    # your app, next to it
cd myapp
npm install                                          # shadow-cljs needs node deps
```

You now have:

```
myapp/
├── deps.edn               server + cljs deps
├── shadow-cljs.edn
├── package.json
├── public/index.html
├── src/myapp/
│   ├── components.cljc    your :myapp/* components
│   ├── intents.cljc       your morphs
│   ├── screens.cljc       starter / screen
│   ├── server/main.clj    server entry point
│   └── web/main.cljs      web entry point
├── ios/                   SwiftPM package for the macOS / iOS demo
└── android/               Gradle module for the Compose Desktop demo
```

The starter screen has a counter with `+` and `reset` buttons.

## Run

```bash
wun dev
```

`wun dev` starts the Clojure server on `:8080` and shadow-cljs watch
on `:8081` together; one Ctrl-C stops both. Open
[http://localhost:8081](http://localhost:8081) — your screen renders,
the buttons fire intents, the page title reflects state.

In other terminals:

```bash
wun run ios       # macOS demo via swift run
wun run android   # Compose Desktop demo via gradle run
```

All three clients connect to the same server and update in
lock-step.

## Add a screen

```bash
wun add screen myapp/profile
```

…creates `src/myapp/profile.cljc` with a starter `defscreen`. Edit
the render fn, `(:require [myapp.profile])` from
`src/myapp/server/main.clj` and `src/myapp/web/main.cljs` so the
side-effecting registration runs at startup.

## Add a component

```bash
wun add component myapp/Card
```

Generates the `defcomponent` declaration plus iOS Swift + Android
Kotlin renderers, and splices the registration into both example
packs' `WunExample.swift` / `WunExample.kt`. The CLI is idempotent —
re-running detects existing files and skips them with a hint.

## Add an intent

```bash
wun add intent myapp/log-in
```

Generates a `definent` stub with a Malli `:params` schema and a
placeholder morph. Wire it to a `:wun/Button {:on-press ...}` in a
screen render fn.

## Verify

```bash
wun status           # per-component coverage matrix
wun doctor           # env check
```

`wun status` shows which components have native renderers on each
platform vs. which fall back to a server-rendered WebFrame. Aim for
`✓` across the board for components users see often; WebFrame fallback
is fine for niche / admin-only screens.

## Next

- [Components](../concepts/components/) — the namespaced vocabulary
- [Screens](../concepts/screens/) — paths, meta, presentation
- [Intents](../concepts/intents/) — morphs, validation, optimistic prediction
- [CLI reference](../reference/cli/) — every subcommand
