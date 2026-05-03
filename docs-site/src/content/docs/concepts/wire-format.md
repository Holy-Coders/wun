---
title: Wire format
description: The patch envelope, intent envelope, and Hiccup-shaped tree.
---

> **TL;DR.** Two envelopes: a **patch envelope** (server → client,
> SSE) carrying tree diffs + state + metadata, and an **intent
> envelope** (client → server, POST) carrying a UUID-tagged action.
> Tree shape is Hiccup with namespaced keywords. Transit by default,
> JSON on `X-Wun-Format: json`. Wire envelope is currently at **v2**;
> v1 is still served on request for older native clients.

Wun's wire format is **transit-json** by default and **JSON** when
`X-Wun-Format: json` is requested. Native clients use JSON for
deserialisation simplicity; the web uses transit so keywords +
UUIDs round-trip without coercion.

## Envelope versioning

The client negotiates a wire version at SSE handshake via the
`?envelope=` query param (or `X-Wun-Envelope` header). Server picks
the highest version it supports, falling back to v1 when an older
client asks for it.

| version | adds                                                       |
|---------|------------------------------------------------------------|
| 1       | baseline -- `:replace` / `:insert` / `:remove` ops only    |
| 2       | `:children` op for keyed list reorder/insert/remove        |
| 2       | `:csrf-token`, `:envelope-version`, `:resync?`, `:theme` envelope fields |

## Patch envelope (server → client, SSE)

```clojure
{:envelope-version 2
 :patches      [{:op :replace  :path [...] :value <subtree>}
                {:op :insert   :path [...] :value <node>}
                {:op :remove   :path [...]}
                {:op :children :path [...] :order [{:key k :existing? true|false :value <node>?}...]}]
 :status       :ok        ;; or :error
 :state        {...}      ;; mirror of authoritative app-state
 :resolves-intent #uuid   ;; optional; pairs with a pending POST
 :conn-id      "..."      ;; first envelope on a new connection
 :csrf-token   "..."      ;; HMAC-bound token; client echoes on /intent
 :screen-stack [:counter/main :app/about]
 :presentations [:push :modal]
 :meta         {:title "..." :description "..." :theme-color "..." :og {...}}
 :theme        {:wun.color/primary "#0a66c2" :wun.spacing/md 16 ...}
 :resync?      true       ;; backpressure-driven full re-render
 :error        {...}}     ;; only when :status :error
```

| key                | meaning                                                          |
|--------------------|------------------------------------------------------------------|
| `:envelope-version`| Wire version negotiated at handshake. Always present.            |
| `:patches`         | JSON-Patch-style ops with Hiccup-aware indexing (skips tag/props slots). |
| `:state`           | Snapshot of the server's per-conn slice. Lets clients run optimistic morphs. |
| `:resolves-intent` | UUID matching a still-pending intent. Drops it from the queue.   |
| `:conn-id`         | Server-assigned id; client echoes it on `/intent` POSTs.         |
| `:csrf-token`      | HMAC-SHA256 token bound to session/conn-id. Echoed on `/intent`. |
| `:screen-stack`    | Per-connection screen stack; top is the visible screen.          |
| `:presentations`   | Parallel array; each entry is `:push` or `:modal`.               |
| `:meta`            | Page metadata; only included when changed since last envelope.   |
| `:theme`           | Effective design-token map; only included when the cascade changes. |
| `:resync?`         | True when this envelope is a full re-render after backpressure.  |

## Intent envelope (client → server, POST `/intent`)

```clojure
{:intent     :counter/inc
 :params     {}
 :id         "abc-1234-..."        ;; UUID; also the dedup key
 :conn-id    "..."                  ;; for framework intents (navigate / pop)
 :csrf-token "..."}                 ;; required when WUN_CSRF_REQUIRED=true (default)
```

CSRF token also accepted as the `X-Wun-CSRF` header. The server
prefers the header when both are present.

The server validates `:params` against the registered schema, runs
the morph if validation passes, and broadcasts the resulting tree
change tagged with `:resolves-intent <id>`.

If the intent's `:id` is already in the dedup cache (1024-entry
LRU) the server returns the cached response without re-running the
morph — making client-side retry safe.

## Hiccup tree shape

Identical across all clients:

```clojure
[:wun/Stack {:gap :wun.spacing/md :padding :wun.spacing/lg}
 [:wun/Heading {:level 1} "Counter"]
 [:wun/Text {:variant :h2} "0"]
 [:wun/Stack {:direction :row :gap :wun.spacing/sm}
  [:wun/Button {:on-press {:intent :counter/dec :params {}}} "-"]
  [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]]]
```

- First element of a vector is a namespaced keyword (the component).
- Second is an optional props map.
- Remaining elements are children — strings, numbers, nested vectors.
- Action handlers (`:on-press`, `:on-change`, `:on-toggle`) carry an
  intent ref `{:intent :ns/name :params {...}}` — data, not closures.
- **Theme tokens** like `:wun.color/primary` and `:wun.spacing/md`
  are namespaced keywords the server resolves before shipping; clients
  see already-resolved literal values. See
  [Theme primitives](/concepts/theme/).

### Keyed children

Wire v2 reduces wire size + preserves child identity (focus, scroll
position) on reorders by **keying children**:

```clojure
[:wun/List {}
 [:myapp/Row {:key "u-1"} "Alice"]
 [:myapp/Row {:key "u-2"} "Bob"]
 [:myapp/Row {:key "u-3"} "Carol"]]
```

When every child of a parent carries `:key`, the differ matches
old↔new by key and emits a single `:children` op describing the new
ordering, instead of position-based replaces.

## Patch ops

| op          | effect                                                                |
|-------------|-----------------------------------------------------------------------|
| `:replace`  | Replace the subtree at `:path` with `:value`.                         |
| `:insert`   | Insert `:value` as a new child at `:path`.                            |
| `:remove`   | Delete the subtree at `:path`.                                        |
| `:children` | (v2) Reorder the children at `:path` per `:order`; new entries inline a `:value`. |

Path indexing is Hiccup-aware: `[0 2]` means "first child of root,
third child of that element" — counting children only, skipping
the `(tag, props)` slots.

## SSE stream framing

```
event: patch
data: <transit-json or json envelope>

event: heartbeat
data: {"type":"ping","ts":1736198400000}

```

The server emits `event: heartbeat` envelopes on a configurable
interval (default 25s, `WUN_HEARTBEAT_INTERVAL_SECS` to override) so
the client's watchdog can detect dead-but-undetected proxies.
Heartbeats don't carry patches — the client just resets its
last-frame-seen timer.

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

## Backpressure resync

When the server's per-conn outbound buffer fills (slow client,
saturated network), `offer!` returns false. Wun marks the conn
**stale** and on the next successful broadcast forces a full
re-render: `prior-tree` is reset to nil so the diff produces a
single `:replace` at root, and the envelope ships with
`:resync? true` so the client can clear its pending-intents queue.

## Read next

- [Intents](/concepts/intents/) — the `:morph` and pending-queue
  semantics behind the intent envelope.
- [Capability negotiation](/concepts/capabilities/) — how the tree
  is rewritten for each client before patches are emitted.
- [Theme primitives](/concepts/theme/) — design tokens that cascade
  server → client.
- [Reconnect & retry](/architecture/reconnect/) — what the bootstrap
  envelope looks like when the SSE stream re-opens.
- [Security](/concepts/security/) — CSRF token issuance, rate
  limits, the session-rotation endpoint.
