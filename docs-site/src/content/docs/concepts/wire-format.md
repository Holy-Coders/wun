---
title: Wire format
description: The patch envelope, intent envelope, and Hiccup-shaped tree.
---

> **TL;DR.** Two envelopes: a **patch envelope** (server → client,
> SSE) carrying tree diffs + state + metadata, and an **intent
> envelope** (client → server, POST) carrying a UUID-tagged action.
> Tree shape is Hiccup with namespaced keywords. Transit by default,
> JSON on `X-Wun-Format: json`.

Wun's wire format is **transit-json** by default and **JSON** when
`X-Wun-Format: json` is requested. Native clients use JSON for
deserialisation simplicity; the web uses transit so keywords +
UUIDs round-trip without coercion.

## Patch envelope (server → client, SSE)

```clojure
{:patches      [{:op :replace :path [...] :value <subtree>}
                {:op :insert  :path [...] :value <node>}
                {:op :remove  :path [...]}]
 :status       :ok        ;; or :error
 :state        {...}      ;; mirror of authoritative app-state
 :resolves-intent #uuid   ;; optional; pairs with a pending POST
 :conn-id      "..."      ;; first envelope on a new connection
 :screen-stack [:counter/main :app/about]
 :presentations [:push :modal]
 :meta         {:title "..." :description "..." :theme-color "..." :og {...}}
 :error        {...}}     ;; only when :status :error
```

| key                | meaning                                                          |
|--------------------|------------------------------------------------------------------|
| `:patches`         | JSON-Patch-style ops with Hiccup-aware indexing (skips tag/props slots). |
| `:state`           | Snapshot of the server's `app-state`. Lets clients run optimistic morphs. |
| `:resolves-intent` | UUID matching a still-pending intent. Drops it from the queue.   |
| `:conn-id`         | Server-assigned id; client echoes it on `/intent` POSTs.         |
| `:screen-stack`    | Per-connection screen stack; top is the visible screen.          |
| `:presentations`   | Parallel array; each entry is `:push` or `:modal`.               |
| `:meta`            | Page metadata; only included when changed since last envelope.   |

## Intent envelope (client → server, POST `/intent`)

```clojure
{:intent  :counter/inc
 :params  {}
 :id      "abc-1234-..."        ;; UUID; also the dedup key
 :conn-id "..."}                ;; for framework intents (navigate / pop)
```

The server validates `:params` against the registered schema, runs
the morph if validation passes, and broadcasts the resulting tree
change tagged with `:resolves-intent <id>`.

If the intent's `:id` is already in the dedup cache (1024-entry
LRU) the server returns the cached response without re-running the
morph — making client-side retry safe.

## Hiccup tree shape

Identical across all clients:

```clojure
[:wun/Stack {:gap 12 :padding 24}
 [:wun/Heading {:level 1} "Counter"]
 [:wun/Text {:variant :h2} "0"]
 [:wun/Stack {:direction :row :gap 8}
  [:wun/Button {:on-press {:intent :counter/dec :params {}}} "-"]
  [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]]]
```

- First element of a vector is a namespaced keyword (the component).
- Second is an optional props map.
- Remaining elements are children — strings, numbers, nested vectors.
- Action handlers (`:on-press`, `:on-change`, `:on-toggle`) carry an
  intent ref `{:intent :ns/name :params {...}}` — data, not closures.

## Patch ops

| op        | effect                                         |
|-----------|------------------------------------------------|
| `:replace`| Replace the subtree at `:path` with `:value`.  |
| `:insert` | Insert `:value` as a new child at `:path`.     |
| `:remove` | Delete the subtree at `:path`.                 |

Path indexing is Hiccup-aware: `[0 2]` means "first child of root,
third child of that element" — counting children only, skipping
the `(tag, props)` slots.

## SSE stream framing

```
event: patch
data: <transit-json or json envelope>

```

(Trailing blank line is the SSE frame terminator.) The server emits
`:wun-probe` SSE comments periodically so the GC can detect dead
connections.

## Errors

Bad intent params:

```json
{"status": "error",
 "resolves-intent": "abc-1234-...",
 "error": {"intent": "counter/by",
           "params": {"n": "oops"},
           "explanation": {"n": ["should be an integer"]}}}
```

Returned at HTTP 400 (intent endpoint) or via SSE with
`:status :error`.

## Read next

- [Intents](/concepts/intents/) — the `:morph` and pending-queue
  semantics behind the intent envelope.
- [Capability negotiation](/concepts/capabilities/) — how the tree
  is rewritten for each client before patches are emitted.
- [Reconnect & retry](/architecture/reconnect/) — what the bootstrap
  envelope looks like when the SSE stream re-opens.
