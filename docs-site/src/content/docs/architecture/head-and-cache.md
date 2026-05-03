---
title: Head & hot-cache
description: How Wun handles document head metadata and survives reconnects.
---

import { Aside } from '@astrojs/starlight/components';

> **TL;DR.** `:meta` is per-screen page metadata; it rides on
> every patch envelope and is applied by each platform: web sets
> `<title>`, iOS sets `.navigationTitle`, Android sets the Compose
> Window title. Hot-cache snapshots persist locally so cold-start
> hydration shows last-known UI before the SSE handshake completes.

This page mirrors `docs/architecture/head-and-cache.md` in the
monorepo. It captures the LiveView/Hotwire mapping for two adjacent
concerns: cross-platform "head" metadata and resilience to drops +
reloads.

| concept            | Hotwire / LiveView                                     | Wun mapping                                  |
|--------------------|--------------------------------------------------------|----------------------------------------------|
| document head      | `<head>` + Turbo head merging                          | per-screen `:meta` fn → web DOM, native title |
| page title         | `<title>` / `assign(:page_title, …)`                   | `:meta {:title …}` patched per envelope       |
| visit/snapshot cache | Turbo `data-turbo-permanent` + cache                  | localStorage / UserDefaults / Preferences     |
| reconnecting       | LiveView `phx-loading-states`, `phx-connected`         | `wun-offline` CSS class + native dimming      |
| "stale ok"         | LiveView shows previous DOM during reconnect           | last-known tree stays rendered                |
| path configuration | Hotwire Native `path-configuration.json`               | `:present :modal` on `defscreen`              |

## Hot-cache persistence

After every server envelope, each client persists the last-known
`{tree, state, screen-stack, meta}` to local storage. On cold start
we hydrate from that cache **before** opening the SSE stream so the
user sees prior UI instantly on reload.

| platform | storage                   | format                  |
|----------|---------------------------|-------------------------|
| Web      | `localStorage`            | transit-json            |
| iOS/macOS| `UserDefaults.standard`   | JSON via `JSONEncoder`  |
| Android  | `java.util.prefs.Preferences` (user root, node `wun/snapshots`) | kotlinx-serialization JSON |

Snapshots are bucketed by **path** (one entry per route), tagged
with a wall-clock save time, and discarded after 24h. The
persisted snapshot is **advisory** — authoritative state lives on
the server. The next envelope replaces it.

<Aside type="note">
We do NOT persist `pending` intents or `conn-id`. After a reload
those are stale; replaying them risks double-applying actions the
user already performed in the previous JS context.
</Aside>

## Offline-UI signal

When the SSE stream drops, the last-known UI **stays on screen**.
It just dims, and a banner surfaces the reconnect status:

| platform | mechanism                                                         |
|----------|-------------------------------------------------------------------|
| Web      | `<body class="wun-offline">`; CSS dims `#app` + shows top banner  |
| iOS/macOS| `vm.status != "connected"` drives `.opacity(0.55)` on the tree    |
| Android  | `Modifier.alpha(...)` driven by status, 200ms tween               |

Borrowed from LiveView's `phx-disconnected` body class.

## Reconnect semantics

1. SSE drops → client adds `wun-offline` class, but keeps rendering
   the last tree.
2. EventSource auto-retries (web); native clients use exponential
   backoff (1s, 2s, 4s, …, capped at 30s).
3. On reconnect, `bus/replay-pending!` re-POSTs every still-pending
   intent. Server-side dedup (LRU 1024 keyed by intent UUID)
   ensures anything already processed returns its cached response.
4. The bootstrap envelope arrives. Client diffs against its
   prior tree, applies patches; the predicted state converges on
   the authoritative tree.

The user-visible effect: clicks during a brief outage feel
instantaneous, then either confirm silently (server already had
them) or land on reconnect (server hadn't).

## Read next

- [Reconnect & retry](/architecture/reconnect/) — the full
  pending-queue + LRU-dedup story.
- [Path configuration](/architecture/path-config/) — `:present
  :modal` vs `:push` and the per-connection screen stack.
- [Screens](/concepts/screens/) — how `:meta` is declared.
