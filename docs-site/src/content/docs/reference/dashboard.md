---
title: Live dashboard
description: Phoenix LiveDashboard-style live introspection for a running Wun server. Connections, intent metrics, recent telemetry — rendered as a regular Wun screen.
---

A live dev-server dashboard. Active SSE connections, per-intent
counters, recent telemetry events, registry sizes — all in one
auto-refreshing screen.

The dashboard is itself a Wun screen (`defscreen :wun.dashboard/main`),
so it dogfoods the framework: same patch pipeline, same wire
envelope, same SSE channel as user screens. There is no
privileged path.

## Mount it

The framework does not auto-mount. From your app's server init:

```clojure
(ns myapp.server.init
  (:require [wun.server.dashboard :as dashboard]))

(defn start! []
  (dashboard/install!)
  ;; ... start your Pedestal service ...
  )
```

Then visit <code>http://localhost:8080/_wun/dashboard</code>.

`install!` is idempotent — calling it more than once on the same
JVM is a no-op.

## Production posture

Dashboard mounting is **refused** when `WUN_PROFILE=prod`. To
opt-in on a production host (for example, behind a VPN-only
admin route), set both:

```bash
WUN_PROFILE=prod WUN_DASHBOARD_FORCE=1
```

The dashboard exposes per-connection state, recent telemetry
events, and the full intent vocabulary — useful for debugging,
not something to expose to the open internet by default.

## What it shows

| Section            | Source                                                   |
|--------------------|----------------------------------------------------------|
| Active connections | `@wun.server.state/connections` — conn-id, screen, caps  |
| Intent metrics     | rolling counters per `:wun/intent.applied` / `.rejected` |
| Recent telemetry   | last 200 events, all event keys                          |
| Registry sizes     | `defcomponent` / `defscreen` / `defintent` counts        |
| Uptime             | wall time since `install!` was called                    |

The render fn is pure and reads everything from per-connection
state under `:wun.dashboard/snapshot`. A 1 Hz server-side ticker
walks active dashboard connections, swaps a fresh snapshot into
each, and triggers a normal broadcast — so the dashboard updates
through the same patch pipeline as any other screen.

## Limits

This is a live snapshot tool, not a metrics backend.

- No persistence: events and counters reset when the JVM restarts.
- No sliding windows or time-series buckets — counters are
  cumulative since `install!`.
- Read-only: no "kick this connection" or live state editing.
- No charts. Tables and badges only.

For long-horizon metrics, register your own
`wun.server.telemetry/register-sink!` that forwards to
Prometheus / OpenTelemetry / your aggregator of choice. The
dashboard's tap is one such sink — read its source for a
template.
