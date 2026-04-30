# Wun

> Clojure-first cross-platform server-driven UI framework.

The server is the source of truth for UI state and emits intent-shaped UI
trees as patches over a single SSE channel per client. Clients maintain a
local mirror of the tree, apply patches, render natively, and dispatch user
actions as data-shaped intents back to the server. Intents are defined once
in `.cljc` with a shared `morph` function that runs on both server
(authoritative) and client (optimistic prediction).

Components are a namespaced vocabulary defined as data; each platform binds
keywords to native renderers; unsupported components fall back to webview
frames seamlessly via Hotwire Native.

Mental model: Datastar's data flow + Hotwire's transport + Clojure's
code-as-data + SDUI's component vocabulary.

## Repo layout

This is a **monorepo** for the four sub-projects called for in the brief.
Each subdirectory is self-contained and can be split into its own repo at
any time with `git subtree split`.

| dir                | language       | status                                  |
|--------------------|----------------|-----------------------------------------|
| `wun-shared/`      | Clojure (cljc) | open registries shared on both sides    |
| `wun-server/`      | Clojure        | phase 0 → 2.H                           |
| `wun-web/`         | ClojureScript  | phase 0 → 1.F                           |
| `wun-ios/`         | Swift package  | phase 2.A → 2.H                         |
| `wun-ios-example/` | Swift package  | reference user-component (2.H demo)     |
| `wun-android/`     | Kotlin lib     | empty stub (phase 3)                    |

## Status: phase 0 + slice 1.A landed

Phase 0 wired the smallest end-to-end loop: server is the source of
truth, emits patch envelopes over SSE, web client renders, intents POST
back. Slice 1.A promotes the macros and registries to first-class
shared code:

- `wun-shared/` carries `defcomponent` / `defscreen` / `definent` plus
  the open registries each one feeds.
- The foundational `:wun/Stack` / `:wun/Text` / `:wun/Button`
  vocabulary is registered via `defcomponent` -- the same API user
  code uses. There is no privileged path between framework and user.
- The counter app (`:counter/main` screen + `:counter/inc/dec/reset`
  intents) is ordinary user-namespace code in `wun.app.counter`,
  registered through the same `defscreen` / `definent` calls.
- The server's render pipeline reads from the screen registry; the
  web client's renderer dispatches via an open per-platform renderer
  registry (`wun.web.renderers`), with `:wun/*` DOM bindings split out
  to `wun.web.foundation`.

Slice 1.B then promotes the wire from "full-tree replace each frame"
to real structural diffing. Server keeps a per-connection memoized
prior tree; on each broadcast it diffs the current tree against the
prior and emits only `:replace` / `:insert` / `:remove` patches at
the smallest path that changed. Frame size for a single counter
increment dropped from ~673 B (full tree) to ~169 B (one deep
`:replace`).

Slice 1.C lights up the brief's marquee thesis: one morph fn defined
in shared `.cljc` runs on both server (authoritative) and client
(optimistic prediction). The wire envelope grows a `:state` field;
the client mirrors confirmed-state, keeps a pending-intents queue,
and renders display = `(reduce morph confirmed-state pending)`. When
the server's confirmation arrives tagged with `:resolves-intent`,
the matching pending entry drops and the display recomputes -- a
match collapses to no visible change, a mismatch converges the UI to
the server's authoritative version.

What's still phase 1+: default loading/error/skeletons, capability
negotiation, native clients, WebFrame fallback, devtools, prop-aware
patch ops, key-aware list reordering.

### Run it

```bash
# 1. build the cljs client (one-time, ~10s)
cd wun-web    && clojure -M:build

# 2. start the server (also serves the web client at /)
cd wun-server && clojure -M:run

# open http://localhost:8080
```

Click the buttons; every connected tab updates because the server
broadcasts the new tree to all SSE connections.

### What's verified end-to-end

- `GET /wun` emits a `:replace`-at-root patch envelope on connect, with
  a tree built by the registered `:counter/main` screen rendering
  through the registered `:wun/*` vocabulary.
- `POST /intent` looks up the intent in the shared registry, applies
  the morph, and broadcasts the new tree to every open SSE connection.
  Multi-client broadcast confirmed; both clients see the same trail.
- Counter trail across `inc inc inc dec reset inc`:
  `0 → 1 → 2 → 3 → 2 → 0 → 1`.
- Static asset serving with path-traversal protection (`/js/../../etc/passwd`
  → 404).
- Transit-json round-trips on the wire including `:resolves-intent` UUIDs.
- cljs build pulls `wun-shared` via `:local/root` and bundles the
  shared `.cljc` registries plus the foundational and app namespaces.

### A note on the stack

The brief calls for **Pedestal** on the server, **shadow-cljs + reagent**
on the web. Phase 0 ships with the JDK's built-in `HttpServer` and
`cljs.main` + a vanilla-DOM renderer because the development sandbox we
built it in cannot reach Clojars (only Maven Central). The substitution is
transport- and view-layer only:

- The wire format is identical (transit-json patch envelopes, namespaced
  Hiccup component trees, intent envelopes with UUIDs).
- The intent semantics are identical (`definent` registers a `:morph`,
  applied server-authoritatively, broadcasts a full tree).
- The component vocabulary is identical (`:wun/Stack`, `:wun/Text`,
  `:wun/Button`, with `defmulti` dispatch on the keyword).

Phase 1 swaps Pedestal in (interceptor chain, content negotiation, NIO
SSE) and reagent in (proper React reconciler, hot reload), without
touching the wire format or the brief's API surface.

## Working principles

- The macros define the universe -- `defcomponent` / `defscreen` /
  `definent`. Push back hard before adding API surface.
- Framework code uses the same APIs as user code. No privileged path.
- Intent definitions over framework knobs. Behavior in intent metadata,
  not config or middleware stacks.
- Pure functions everywhere they're possible. Side effects in named,
  identified places (`persist`, `fetch`, action handlers).
- Patches over re-renders. The server only emits what changed; the client
  only re-renders what was patched.
- Native first, webview last. WebFrame is graceful fallback, not default.
  Every WebFrame in production is an implicit roadmap item.
- One source of truth: UI state on the server. Local state on the client
  only where it doesn't belong on the server (form drafts, scroll
  position, ephemeral focus).

## Wire format

Single SSE channel per client, server-initiated. Patch envelope:

```clojure
{:patches [{:op :replace :path [...] :value ...}
           {:op :insert  :path [...] :value ...}
           {:op :remove  :path [...]}]
 :resolves-intent #uuid "..."   ; optional
 :status :ok                    ; or :error
 :error  {...}}                 ; when :error
```

Intent envelope (POST `/intent`):

```clojure
{:intent :counter/inc
 :params {}
 :id     #uuid "..."}
```

UI tree -- Hiccup-shaped, namespaced components, actions as data:

```clojure
[:wun/Stack {}
 [:wun/Text {:variant :h1} "Aaron"]
 [:wun/Button {:on-press {:intent :user/edit :params {:id 42}}} "Edit"]]
```

The wire format is namespace-agnostic. `:wun/*` and `:myapp/*` flow
identically.

## Phase plan (summary)

- **Phase 0** -- spike. Validate the loop feels right. *Done.*
- **Phase 1** -- server foundations + web client.
  - **1.A** open registries via shared `defcomponent`/`defscreen`/`definent`.
    *Done.*
  - **1.B** tree diffing per connection; path-aware patch ops on the
    client. Diff + apply live in shared `wun.diff` (cljc) so producer
    and consumer can't drift. *Done.*
  - **1.C** shared `.cljc` morphs run on web for optimistic UI;
    reconciliation via `:resolves-intent`. Envelope carries `:state`
    so the client mirrors the screen state and can run the same
    `wun.intents/apply-intent` morphs the server runs. *Done.*
  - **1.D-Malli** intent `:params` schemas validate at both wire
    boundaries; server returns 400 with a humanised explanation,
    client logs and drops the call before optimistic prediction or
    POST. *Done.*
  - **1.D-Pedestal** server transport runs on Pedestal's
    interceptor chain on Jetty. SSE via `sse/start-event-stream`,
    transit-json bodies via `body-params`, custom static interceptor
    after routing. *Done.*
  - **1.D-shadow-cljs** build replaces `cljs.main` with shadow-cljs;
    `:advanced` Closure compilation cuts the bundle from ~2.25 MB
    to ~533 KB (with reagent + Malli) / ~359 KB (without reagent).
    *Done.*
  - **1.D-reagent** foundational `:wun/*` renderers return reagent
    Hiccup; a single reagent root re-renders when the
    `display-tree` reagent atom changes, and React's reconciler
    handles incremental DOM updates. *Done.*
  - **1.E** per-connection tree eviction (the brief's risk #5) and
    client reconnection awareness. Server: scheduled GC sweep
    actively probes each channel via a `:wun-probe` SSE comment
    and evicts whatever Pedestal has closed. Client: pending
    intents tagged with timestamps; periodic JS-interval GC drops
    entries older than 30 s; bootstrap-frame detection (a
    `:replace` at root) clears pending across reconnect.
  - **1.F** capability negotiation. Client builds a caps map from
    its registered web renderers and sends `?caps=wun/Stack@1,...`
    on the SSE URL (EventSource can't set custom headers; native
    clients in phase 2 use `X-Wun-Capabilities`). Server parses
    per-connection, applies `wun.capabilities/substitute` to the
    rendered tree before diffing. Unsupported subtrees collapse to
    `[:wun/WebFrame {:missing <kw>}]` at the smallest containing
    level; the web client renders WebFrame as a placeholder
    today, and the iOS/Android phases swap in a real Hotwire
    Native frame.
- **Phase 2** -- iOS native. SwiftUI renderers, WebFrame fallback,
  capability negotiation end-to-end.
  - **2.A** server content-negotiates JSON (`Accept: application/json`
    or `?fmt=json`) alongside transit-json; Swift package scaffolds
    the wire-shape types (`JSON`, `WunNode`, `Patch`, `Envelope`)
    with Codable + Equatable, no networking yet. *Done.*
  - **2.B** Swift port of `wun.diff/apply-patches` (Hiccup-aware
    indexing); `TreeMirror` actor; `SSEClient` with hand-rolled
    byte-level line splitter (Foundation's `bytes.lines` collapses
    empty lines, which SSE needs as frame terminators); `wun-smoke`
    executable for end-to-end. iOS counter trail across server
    intents matches the cljc smoke. *Done.*
  - **2.C** `WunComponent` registry + `WunView` SwiftUI driver +
    SwiftUI renderers for `:wun/Stack` and `:wun/Text`. `TreeStore`
    (`@MainActor` + `ObservableObject`) sits alongside the actor
    `TreeMirror` so SwiftUI views can deref the tree reactively.
    `WunFoundation.register(into:)` mirrors the cljc-side
    bootstrap; user code registers components through the same
    API. *Done.*
  - **2.D** the rest of the brief's foundational vocabulary --
    `:wun/Image`, `:wun/Button`, `:wun/Card`, `:wun/Avatar`,
    `:wun/Input`, `:wun/List`, `:wun/Spacer`, `:wun/ScrollView` --
    each as a SwiftUI renderer in `Foundation/`. `Wun.intentDispatcher`
    is the global hook Button/Input fire when the user acts; phase
    2.E plugs in a real POST. iOS client now advertises 11 caps; the
    server passes the tree through without substitution. *Done.*
  - **2.E** action dispatcher: `IntentDispatcher` POSTs JSON envelopes
    to `/intent` with a generated UUID; on 400, decodes the error
    envelope and forwards the Malli explanation through an `onError`
    callback. `dispatcher.install()` swaps the global
    `Wun.intentDispatcher`. `wun-smoke` now fires inc / by 5 /
    by "oops" / reset from Swift; the `resolves-intent` UUIDs match
    each dispatch's id, the 400 case lights up `onError` with
    `{n: ["should be an integer"]}`. *Done.*
  - **2.F** `:wun/WebFrame` fallback. Server emits
    `[:wun/WebFrame {:missing :wun/Foo :src "/web-frames/wun%2FFoo"}]`
    when capability negotiation finds an unsupported subtree.
    Server route `/web-frames/<key>` returns HTML the client
    displays in a WKWebView via `WunWebFrame.render` (cross-platform
    SwiftUI/WKWebView bridge for iOS + macOS). `Wun.serverBase`
    resolves the relative `:src`. `wun.capabilities/substitute`
    grew an optional `src-builder` so the URL-encoding stays
    server-side and the cljc stays pure. The HTML is currently a
    stub diagnostic; rendering the actual subtree via the web
    cljs renderers is a later-phase improvement. *Done.*
  - **2.G** native clients now use `X-Wun-Capabilities` and
    `X-Wun-Format` request headers as the brief specifies; server
    reads either header or query-string. SSEClient takes
    `headers: [String:String]`; web stays on the query-string form
    because EventSource can't set custom headers. *Done.*
  - **2.H** reference user component shipped as a separate Swift
    package: `wun-ios-example/` defines `WunExample` (registers
    `:myapp/Greeting`) and an `example-smoke` executable. On the
    server, `myapp.components` carries the matching defcomponent
    spec; the counter screen now opens with a `:myapp/Greeting`.
    Clients that advertise the cap render it natively; clients
    that don't see a `WebFrame` substituted at the smallest
    containing subtree -- demonstrating the brief's "no privileged
    path" thesis on iOS. *Done.*
- **Phase 3** -- Android. Compose renderers, parity with iOS.
- **Phase 4** -- shared morphs on native via SCI in JavaScriptCore / V8.
- **Phase 5** -- opt-in CRDTs for collaborative components.
- **Phase 6** -- hardening, ecosystem, theming, hot reload, starter
  templates.
