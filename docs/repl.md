# Wun REPL workflow

A productive Clojure-flavoured rebound on a sluggish edit-compile-restart
loop is a substantial chunk of why you'd choose Wun over Phoenix or
Rails. This guide gets you there.

## Server REPL

```bash
cd wun-server
clojure -M:dev
```

Drops you in `user`. Common moves:

```clj
(require '[wun.server.core :as core])
(core/start!)             ; starts on :8080
;; ... edit any cljc/clj/cljs file in the monorepo ...
(require 'wun.app.counter :reload)   ; or any namespace you changed
;; The next intent re-renders against the fresh code.
;; defcomponent / defscreen / defintent registries are open --
;; redefining is idempotent, no restart needed.

(core/stop!)
```

To swap the default port:

```clj
(core/start! {:port 4000})
```

To inspect live state:

```clj
(require '[wun.server.state :as st])
@st/connections
@st/conn-states
```

## Web REPL (shadow-cljs nREPL)

```bash
cd wun-web
npx shadow-cljs watch app
```

In a second terminal:

```bash
npx shadow-cljs cljs-repl app
```

You're now in a ClojureScript REPL connected to the running browser.
Reload code:

```clj
(in-ns 'wun.web.foundation)
;; Edit, save. Shadow autoreloads via :devtools :after-load.
(require '[wun.web.intent-bus :as bus])
(bus/recompute!)              ; force a re-render
```

The `:after-load` hook in `shadow-cljs.edn` calls
`wun.web.core/after-reload` which re-mounts Replicant against the
existing DOM root, preserving DOM identity (and thus text-input
caret position) across reloads.

## Editor integration

- **Cursive (IntelliJ)**: connect to `localhost:7888` (the default
  shadow-cljs nREPL) and select build `:app` for the cljs side.
- **CIDER (Emacs)**: `cider-connect` on the same port; M-x
  `cider-connect-cljs` for the browser side.
- **Calva (VS Code)**: "Connect to a Running REPL Server" → "shadow-cljs"
  → pick `:app`.

## Common pitfalls

- **Stale registries**: `defcomponent` / `defscreen` / `defintent` are
  open — re-`require :reload` the namespace and the next intent
  picks up the change. The web client also runs the morph; if the
  client state diverges from server, hit refresh.
- **Side-effecting requires**: namespaces under `wun.app.*` or your
  app's component pack register on first load. After a major refactor,
  bounce the REPL once if you're seeing ghost components from
  previous defs.
- **wire-format changes**: any change to `wun.diff` /
  `wun.server.wire` / native `Diff.{swift,kt}` requires the four
  platforms to update in lockstep. The server's `:test-noclojars`
  alias guards the clj+cljc side; the iOS XCTest and Android
  kotlin.test suites guard the native side. Run all of them after
  touching the wire.
