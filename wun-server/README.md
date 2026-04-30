# wun-server

Phase 0 spike of the Wun server. See the top-level [README](../README.md) for
the project brief.

## Run

```bash
clojure -M:run
```

The server binds on `:8080` and exposes:

| route          | verb       | purpose                                                 |
|----------------|------------|---------------------------------------------------------|
| `/wun`         | GET        | SSE patch stream. Emits `:replace`-at-root envelopes.   |
| `/intent`      | POST       | Transit-json intent envelope.                           |
| `/`            | GET        | Static files from `../wun-web/public` (configurable).   |

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

### Static file root

The static handler serves `WUN_STATIC` if set, otherwise
`../wun-web/public`. Pass `:static nil` to `start!` programmatically to
disable static serving entirely.

## Layout

```
src/wun/server/
  core.clj      ;; entry point, lifecycle
  http.clj      ;; JDK HttpServer transport, SSE, static, CORS
  state.clj     ;; app-state atom + connection set
  intents.clj   ;; definent macro + registry + apply-intent
  render.clj    ;; phase-0 hardcoded screen
  wire.clj      ;; transit + patch envelope helpers
```

## Phase 0 caveats

- **Pedestal is not used.** The brief calls for Pedestal; phase 0 ships
  with the JDK's `com.sun.net.httpserver.HttpServer` because the sandbox
  this was built in cannot reach Clojars. Phase 1 swaps Pedestal in --
  the wire format, intent semantics, and component vocabulary stay
  identical, only the transport changes. See the comment at the top of
  `http.clj` for context.
- No diffing. Every intent rebroadcasts the full tree as a single
  `{:op :replace :path []}` patch.
- No per-connection memoised tree, no eviction strategy. Disconnected
  channels stay in the connection set; `core.async/offer!` on a closed
  channel is a silent no-op. Reaping is phase-1 work.
- One OS thread per SSE connection (blocking on a per-connection
  core.async queue). Phase 1 needs Pedestal/Jetty NIO for real load.
- No authorize / persist / capability negotiation. Phase-1 work.
- `morph` returns a new state value for now; the brief's eventual
  contract is `(state, intent) -> [patches]`.
