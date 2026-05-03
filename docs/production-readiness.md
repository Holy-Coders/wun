# Wun production-readiness plan

Tracking the work to bring Wun to LiveView + Hotwire Native parity. Each
phase is a self-contained PR-sized commit on
`claude/production-readiness-planning-1Irov`. Wire-format changes
update every platform in the same commit per CLAUDE.md.

## Phase 0 — Toolchain + plan
- [x] `clojure` CLI + `babashka` available
- [x] This document

## Phase 1 — Server P0 hardening
**No wire change.** Web/iOS/Android continue to work as-is.
- [x] kaocha runner + `test/` skeletons for `wun-server`, `wun-shared`
- [x] Tests for `wun.diff`, `wun.capabilities`, `wun.intents`,
      `wun.screens`, `wun.server.state`, `wun.server.wire`
- [x] Heartbeat envelopes (`{:ping ts}`) + client-side dead-conn detection
- [x] Bounded core.async buffers + snapshot-resync on overflow
- [x] Token-bucket rate limiting per `conn-id` and per source IP on `/intent`
- [x] CSRF: signed double-submit token bound to session
- [x] Session-token rotation + revocation list
- [x] `wun.server.telemetry` interface (vendor-neutral) + structured logging

## Phase 2 — Wire envelope v2 + key-aware diffing
**Coordinated change across server + shared + web + iOS + Android.**
- [ ] `:envelope-version` field (server defaults to v2; serves v1 if asked)
- [ ] Key-aware list diff (`:key` per child enables move/insert/remove)
- [ ] `:replace-root` snapshot op
- [ ] Tests on all four platforms

## Phase 3 — Web rewrite (Replicant, zero React)
- [ ] Remove `reagent`, `react`, `react-dom` from deps
- [ ] Add `no.cjohansen/replicant`
- [ ] Port `wun.web.core`, `intent-bus`, `renderers`, `foundation`
- [ ] Preserve: hydration, optimistic morphs, reconnect, popstate
- [ ] `wun-web/test/` running under shadow-cljs `:node-test`
- [ ] `:wun/Skeleton` loading-state primitive

## Phase 4 — Forms + uploads
- [ ] `:wun/Form` with field/form-level state + error shape
- [ ] Server-side malli validation merge on submit intents
- [ ] `:wun/FileInput` + `/upload` chunked endpoint
- [ ] Upload progress patches and entry lifecycle
- [ ] Tests

## Phase 5 — PubSub + Presence
- [ ] `wun.server.pubsub` interface + in-process atom impl
- [ ] `definent` extension: `:publish` side effect from server morphs
- [ ] `:wun/presence` registry + join/leave broadcast
- [ ] Tests

## Phase 6 — Native parity
- [ ] iOS: envelope v2 + key-aware diff in `Diff.swift` / `Envelope.swift`
- [ ] iOS: HotwireNative integration replacing plain WKWebView
- [ ] iOS: bridge components surface
- [ ] iOS: tests
- [ ] Android: envelope v2 + key-aware diff
- [ ] Android: real `WebView` via Compose interop
- [ ] Android: tests

## Phase 7 — DX + ops polish
- [ ] Integration harness (server boots + drives SSE + asserts patches)
- [ ] REPL workflow doc
- [ ] AST-based migrations via `rewrite-clj`
- [ ] Error-boundary envelope + default error UI
- [ ] Component playground (`/_/playground`)
- [ ] Secrets pattern in templates
- [ ] Production observability doc (Prometheus/OTel wiring guide)

## Out of scope
- Production DB hardening (template territory, not framework)
- Hosted Redis/NATS pubsub impl (interface only)
- Push notification provider integration (bridge surface only)
- Universal-link plumbing (per-app concern)

## Verification gates per platform
- `wun-server`: `clojure -M:test` (kaocha)
- `wun-shared`: `clojure -M:test` (kaocha)
- `wun-web`:    `npx shadow-cljs compile :node-test && node out/node-tests.js`
- `wun-ios`:    `swift build && swift test` (Xcode required; deferred to user box)
- `wun-android`: `gradle compileKotlin test` (Android SDK required for some flows)
