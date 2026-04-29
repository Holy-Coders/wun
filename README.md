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

1. Pedestal app, one route `/wun` (SSE), one hardcoded screen, one
   `definent` (`:counter/inc`).
2. ClojureScript client subscribes to the SSE stream, mirrors the tree,
   renders Hiccup, dispatches the intent on button press.
3. Server applies the morph and re-broadcasts the full tree to all
   connected clients.

That is *all* that's wired up right now. No diffing, no optimistic UI, no
native renderers, no fallback, no capability negotiation. Each of those is
a phase-1+ deliverable.

### Run it

Two terminals.

**Server:**

```bash
cd wun-server
clj -M:run
```

**Web client:**

```bash
cd wun-web
npm install
npm run watch
# open http://localhost:8081
```

Click the buttons; every connected tab updates because the server
broadcasts the new tree to all SSE connections.

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
- **Phase 1** -- server foundations + web client. Tree diffing, optimistic
  UI on web, default loading/error/skeleton, devtools, full registry.
- **Phase 2** -- iOS native. SwiftUI renderers, WebFrame fallback,
  capability negotiation end-to-end.
- **Phase 3** -- Android. Compose renderers, parity with iOS.
- **Phase 4** -- shared morphs on native via SCI in JavaScriptCore / V8.
- **Phase 5** -- opt-in CRDTs for collaborative components.
- **Phase 6** -- hardening, ecosystem, theming, hot reload, starter
  templates.
