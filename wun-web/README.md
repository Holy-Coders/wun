# wun-web

Phase 0 spike of the Wun web client. See the top-level [README](../README.md)
for the project brief.

## Run

```bash
# 1. one-time build (10-20 seconds, slow because Closure :simple)
clojure -M:build

# 2. start wun-server, which serves this directory's public/ at /
cd ../wun-server && clojure -M:run

# open http://localhost:8080
```

For an iterating dev loop, run `clojure -M:watch` in this directory to
rebuild on save (`:none` optimisations, faster). Reload the page to see
changes.

## How it hangs together

```
+--------------------+         SSE patch stream         +-------------+
|  wun-web (cljs)    | <------------------------------- | wun-server  |
|  - tree mirror     |                                  |             |
|  - DOM render      |   POST /intent (transit-json)    |  HttpServer |
|  - intent POST     | -------------------------------> |  + intents  |
+--------------------+                                  +-------------+
```

- `tree-state` is an atom holding the current UI tree. An `add-watch`
  re-renders the DOM whenever it changes.
- `apply-patch!` applies `:replace` patches at root (phase 0 only).
- `render-component` is a `defmulti` keyed on the component keyword.
  `:wun/Stack`, `:wun/Text`, `:wun/Button` are supported. Anything else
  renders a visible `[unknown component ...]` placeholder -- WebFrame
  fallback arrives in phase 2.
- `dispatch-intent!` POSTs the action data shape to
  `<server-base>/intent`. Default `<server-base>` is
  `http://localhost:8080`; override at the JS console with
  `window.WUN_SERVER`.

## Phase 0 caveats

- **shadow-cljs is not used.** The brief calls for shadow-cljs; phase 0
  builds with `cljs.main` directly because the sandbox this was built in
  cannot reach Clojars. Phase 1 reintroduces shadow-cljs (init-fn auto
  call, hot reload, npm interop). The source tree doesn't change; only
  the build driver does.
- **Reagent is not used.** Same reason -- reagent lives on Clojars. The
  renderer in `core.cljs` is hand-rolled to vanilla DOM; swapping it for
  reagent in phase 1 only changes the renderer, not the SSE wiring,
  patch applicator, intent dispatcher, or component vocabulary.
- No optimistic UI. The web client waits for a server patch before
  showing any state change, even though `morph` is a pure function we
  could run locally. That comes in phase 1.
- No devtools panel. Phase 1 adds the patches-in-flight log and registry
  inspector called for in the brief.
