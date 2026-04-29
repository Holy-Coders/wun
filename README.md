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

| dir            | language       | status                       |
|----------------|----------------|------------------------------|
| `wun-server/`  | Clojure        | phase 0 spike                |
| `wun-web/`     | ClojureScript  | phase 0 spike                |
| `wun-ios/`     | Swift package  | empty stub (phase 2)         |
| `wun-android/` | Kotlin lib     | empty stub (phase 3)         |

## Phase 0 deliverable: counter spike

The smallest end-to-end loop that proves the architecture:

1. JDK `HttpServer` + manual SSE on `/wun`, plus a `definent` registry.
2. ClojureScript client subscribes to the SSE stream, mirrors the tree,
   renders Hiccup to vanilla DOM, dispatches intents on button press.
3. Server applies the morph and re-broadcasts the full tree to every
   connected client.

That is *all* that's wired up right now. No diffing, no optimistic UI, no
native renderers, no fallback, no capability negotiation. Each of those is
a phase-1+ deliverable.

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

- `GET /wun` emits a `:replace`-at-root patch envelope on connect.
- `POST /intent` applies the morph and broadcasts the new tree to every
  open SSE connection. Multi-client broadcast confirmed; both clients see
  the same `Counter: 0 → 1 → 2` trail.
- Static asset serving with path-traversal protection (`/js/../../etc/passwd`
  → 404).
- Transit-json round-trips on the wire including `:resolves-intent` UUIDs.

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

- **Phase 0** -- spike. Validate the loop feels right. *(this commit)*
- **Phase 1** -- server foundations + web client. Swap in Pedestal and
  reagent. Tree diffing, optimistic UI on web, default
  loading/error/skeleton, devtools, full registry.
- **Phase 2** -- iOS native. SwiftUI renderers, WebFrame fallback,
  capability negotiation end-to-end.
- **Phase 3** -- Android. Compose renderers, parity with iOS.
- **Phase 4** -- shared morphs on native via SCI in JavaScriptCore / V8.
- **Phase 5** -- opt-in CRDTs for collaborative components.
- **Phase 6** -- hardening, ecosystem, theming, hot reload, starter
  templates.
