# wun-web

Phase 0 spike of the Wun web client. See the top-level [README](../README.md)
for the project brief.

## Run

In one terminal, start the server:

```bash
cd ../wun-server && clj -M:run
```

In another, build and serve the web client:

```bash
npm install
npm run watch
```

shadow-cljs serves `public/` on <http://localhost:8081> and rebuilds on save.
The compiled JS is dropped at `public/js/main.js`.

The client connects to <http://localhost:8080> via SSE and POSTs intents back
to the same origin. CORS is open on the dev server.

## How it hangs together

```
+--------------------+         SSE patch stream         +-------------+
|  wun-web (cljs)    | <------------------------------- | wun-server  |
|  - tree mirror     |                                  |             |
|  - reagent render  |   POST /intent (transit-json)    |  Pedestal   |
|  - intent POST     | -------------------------------> |  + intents  |
+--------------------+                                  +-------------+
```

- `tree-state` is a reagent atom holding the current UI tree.
- `apply-patch!` applies `:replace` patches at root (phase 0 only).
- `render-component` dispatches on component keyword. `:wun/Stack`,
  `:wun/Text`, `:wun/Button` are supported. Anything else renders a visible
  `[unknown component ...]` placeholder -- WebFrame fallback arrives in
  phase 2.
- `dispatch-intent!` POSTs the action data shape back to `/intent`.

## Phase 0 caveats

- No optimistic UI. The web client waits for a server patch before showing
  any state change, even though `morph` is a pure function we could run
  locally. That comes in phase 1.
- Component renderers are inline in `core.cljs`. Phase 1 promotes them to
  `defcomponent` calls in shared `.cljc`.
- No devtools panel. Phase 1 adds the patches-in-flight log and registry
  inspector called for in the brief.
