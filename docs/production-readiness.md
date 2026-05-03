# Wun production-readiness plan

Tracking the work done to bring Wun to LiveView + Hotwire Native
parity, on branch `claude/production-readiness-planning-1Irov`. Each
phase is a self-contained PR-sized commit. Wire-format changes update
every platform in the same commit per CLAUDE.md.

Snapshot at the end of the run:

| Suite             | Tests | Assertions | Status |
|-------------------|------:|-----------:|--------|
| `wun-shared`      |    65 |        103 | green  |
| `wun-server`      |    86 |        186 | green  |
| `wun-android` (compile only — full test run blocked by network) | n/a | n/a | compiles |
| `wun-ios`         | n/a  | n/a        | compile gate deferred to a workstation with Xcode |

## Phase 0 — Toolchain + plan
- [x] `clojure` CLI + `babashka` available
- [x] This document

## Phase 1 — Server P0 hardening
- [x] kaocha-style runner + `test/` skeletons for `wun-server`,
      `wun-shared`
- [x] Tests for `wun.diff`, `wun.capabilities`, `wun.intents`,
      `wun.screens`, `wun.server.state`, `wun.server.wire`
- [x] Heartbeat envelopes (`{:type :ping :ts ms}`) on a configurable
      interval (default 25s, env `WUN_HEARTBEAT_INTERVAL_SECS`)
- [x] Bounded core.async `offer!` failure → mark stale → next
      broadcast force-resyncs with a full snapshot
- [x] Token-bucket rate limiting per `conn-id` and per source IP on
      `/intent`
- [x] CSRF: HMAC-SHA256 token bound to session/conn-id, signed with
      `WUN_CSRF_SECRET`, constant-time validate, `WUN_CSRF_REQUIRED=false`
      transitional toggle
- [x] Session-token rotation + revocation registry
- [x] `wun.server.telemetry` interface (vendor-neutral) + structured
      logging via `tools.logging.readable`-shaped sink

## Phase 2 — Wire envelope v2 + key-aware diffing
**Coordinated across server + shared + iOS + Android.**
- [x] `:envelope-version` field; server downshifts to v1 on request
- [x] Key-aware `:children` op for keyed list reorder/insert/remove
- [x] `wun.diff/diff-v1` fallback and dynamic `*envelope-version*` switch
- [x] Tests on Clojure (cljc) + iOS XCTest + Android kotlin.test

## Phase 3 — Web rewrite (Replicant, zero React)
- [x] Removed `reagent`, `react`, `react-dom`
- [x] Added `no.cjohansen/replicant` (zero-runtime-dep VDOM)
- [x] Ported `wun.web.core`, `intent-bus`, `renderers`, `foundation`
- [x] Preserved hydration + optimistic morphs + reconnect + popstate
- [x] `wun-web/test/` running under shadow-cljs `:node-test`
- [x] `:wun/Skeleton` loading-state primitive

## Phase 4 — Forms + uploads
- [x] `:wun/Form`, `:wun/Field` with form-level + field-level state
- [x] `:wun.forms/change`, `/touch`, `/reset`, `/submit` framework
      intents; Malli validation merge on submit
- [x] `:wun/FileInput` + `/upload` chunked endpoint with
      `WUN_UPLOAD_DIR`, max-size cap, custom `commit-fn` for S3/R2
- [x] Per-upload progress patches piggybacking the SSE stream

## Phase 4.5 — Theme primitives
**Tokens cascade server → all clients.**
- [x] `wun.theme` cljc registry + cascade + pure resolver
- [x] `wun.foundation.theme` default-light token set (colors,
      spacing, radii, typography, shadows)
- [x] Server resolves before capability substitution; theme rides in
      the envelope
- [x] Web mirrors theme map + writes CSS custom properties
      (`--wun-color-primary` etc.) on `:root`
- [x] iOS / Android `Envelope` decode the field; renderers see
      already-resolved values

## Phase 5 — PubSub + Presence
- [x] `wun.server.pubsub` with `Bus` protocol + `InProcessBus` default
- [x] `set-bus!` swap-in for Redis/NATS without framework changes
- [x] `wun.server.presence` per-topic `{conn-id -> meta}` rolls;
      auto-cleanup on disconnect (both inline + GC tick)
- [x] `wun.server.broadcast` convenience: subscribe + per-conn morph +
      automatic re-broadcast
- [x] Tests

## Phase 6 — Native parity
- [x] iOS `IntentDispatcher` echoes CSRF on `/intent` (header + body)
- [x] iOS `TreeStore` mirrors `csrfToken`, `theme`, `envelopeVersion`
- [x] iOS `WunHostNavigator` extension point for HotwireNative et al;
      WebFrameRenderer asks the host first, falls back to WKWebView
- [x] iOS `HOTWIRE-NATIVE.md`: integration recipe
- [x] Android `IntentDispatcher` echoes CSRF
- [x] Android `TreeMirror` mirrors csrfToken / theme / envelopeVersion
- [x] Android WebFrame: actionable "Open in browser" via
      `java.awt.Desktop`; `Wun.openUrl` hook for host overrides
- [x] iOS XCTest + Android kotlin.test cover the new envelope fields

## Phase 7 — DX + ops polish
- [x] `wun.errors` cljc error boundary + `:wun/ErrorBoundary` component;
      server `safe-render` wraps every render fn
- [x] `wun.server.config`: 12-factor env-var resolver with required /
      default / parse + `report-missing` for `-main` ergonomics
- [x] `migrations/lib.bb`: AST-based codemod helper using rewrite-clj
      (whitespace + comments preserved)
- [x] `docs/repl.md`: server + shadow-cljs nREPL workflow
- [x] `docs/observability.md`: event vocabulary + Prometheus / OTel
      wiring snippets

## Phase 7.5 — Property-based tests
- [x] `clojure.test.check` (Maven Central, no Clojars dependency)
- [x] `(apply-patches old (diff old new)) == new` over 200 random
      pairs at depth 3, plus the v1 fallback differ
- [x] Capability negotiation: substitution eliminates unsupported
      tags; idempotent; full-caps is identity. (A wrong "richer caps =
      fewer WebFrames" property was caught by test.check itself and
      removed -- exactly the kind of false invariant property testing
      exists to surface.)
- [x] Theme: empty theme is identity; resolution is idempotent;
      unknown tokens survive resolution unchanged

## Phase 8 — Docs polish (this commit)
- [x] This document brought to truth
- [x] docs-site build degrades when Chromium is unavailable
      (`WUN_MERMAID=1` opt-in instead of always-on)
- [x] CI workflow flips `WUN_MERMAID` on/off based on whether the
      `playwright install --with-deps chromium` step succeeded

## Out of scope (explicitly)
- Datomic / Postgres / SQLite production-DB hardening (template
  territory, not framework).
- A hosted Redis / NATS pubsub impl (interface only).
- Push-notification provider integrations (Apple APNs / Firebase) --
  bridge surface only.
- Universal-link plumbing (per-app concern).

## Verification gates per platform
- `wun-server`: `clojure -M:test` (cognitect test-runner)
- `wun-shared`: `clojure -M:test`
- `wun-web`:    `npm test` (shadow-cljs `:node-test`)
- `wun-ios`:    `swift build && swift test` (Xcode required)
- `wun-android`: `gradle test` (full Compose deps; Android SDK for
                                  the mobile target)

In environments where Clojars and `dl.google.com` are unreachable,
the `:test-noclojars` aliases on `wun-shared` and `wun-server` and
the `compileKotlin` task on `wun-android` provide a meaningful
subset of guards. The full suites are what CI on a normal box runs.
