# wun-server

Phase 0 spike of the Wun server. See the top-level [README](../README.md) for
the project brief.

## Run

```bash
clj -M:run
```

The server binds on `:8080` and exposes:

| route          | verb | purpose                                                |
|----------------|------|--------------------------------------------------------|
| `/wun`         | GET  | SSE patch stream. Emits a `:replace`-at-root envelope. |
| `/intent`      | POST | Transit-json intent envelope.                          |

The SSE event name is `patch`; each `data:` line is a transit-json
envelope of shape:

```clojure
{:patches [{:op :replace :path [] :value <hiccup-tree>}]
 :status  :ok
 :resolves-intent #uuid "..."}   ; optional
```

POST `/intent` accepts:

```clojure
{:intent :counter/inc :params {} :id #uuid "..."}
```

## REPL

```bash
clj -M:dev
```

Then `(require 'user)` and call `(user/restart!)` after edits. State lives in
`wun.server.state/app-state`; the intent registry lives in
`wun.server.intents/registry`.

## Layout

```
src/wun/server/
  core.clj      ;; Pedestal service, routes, SSE wiring
  state.clj     ;; app-state atom + connection set
  intents.clj   ;; definent macro + registry + apply-intent
  render.clj    ;; phase-0 hardcoded screen
  wire.clj      ;; transit + patch envelope helpers
```

## Phase 0 caveats

- No diffing. Every intent rebroadcasts the full tree as a single
  `{:op :replace :path []}` patch.
- No per-connection memoised tree, no eviction strategy.
- No authorize / persist / capability negotiation. These are phase-1 work.
- `morph` returns a new state value for now; the brief's eventual contract
  is `(state, intent) -> [patches]`.
