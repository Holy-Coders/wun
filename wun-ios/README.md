# wun-ios

Swift package for the Wun iOS client. See the top-level
[README](../README.md) for the project brief.

## Build / test

```bash
swift build
swift test
```

## Status

Phase 2.A through 2.H landed. The package does an end-to-end wire
loop against a phase-2-aware server, ships SwiftUI renderers for
the brief's full foundational vocabulary, and falls back to a
WKWebView for any component the client doesn't ship natively.

To see it on screen, see [wun-ios-example](../wun-ios-example) --
that package adds a SwiftUI macOS demo target you can run from
Xcode or `swift run`.

| type / actor       | role                                                           |
|--------------------|----------------------------------------------------------------|
| `JSON`             | Closed recursive variant covering JSON's six shapes.           |
| `WunNode`          | Hiccup-shaped tree node (text / number / bool / component).   |
| `Patch`            | One `:replace`/`:insert`/`:remove` op against a child path.   |
| `Envelope`         | The SSE patch envelope: `patches`, `status`, `state`, `resolves-intent`. |
| `Diff`             | Pure path-aware patch applicator (Swift port of `wun.diff`).   |
| `TreeMirror`       | Actor variant holding tree + state. Non-UI consumers.          |
| `TreeStore`        | `@MainActor` + `ObservableObject` variant for SwiftUI binding. |
| `SSEClient`        | URLSession-bytes streaming + hand-rolled line splitter.        |
| `Registry`         | Open `tag -> WunComponent` registry. Framework + user code share the same API. |
| `WunComponent`     | `(props, children) -> AnyView` -- the registry's value type.   |
| `WunView`          | SwiftUI `View` walking a `WunNode` through a `Registry`.       |
| `WunFoundation`    | Bootstraps `:wun/*` renderers into a `Registry`.               |

Tests: 10 across `EnvelopeTests` (decode + WunNode + JSON round-trip)
and `DiffTests` (replace at root, deep replace, prop changes, insert,
trailing remove, no-props indexing, multi-patch fold).

End-to-end smoke executable lives at `Sources/WunSmoke`. Connect to
a running server and watch frames flow:

```bash
cd ../wun-server && clojure -M:run    # in one terminal
cd ../wun-ios    && swift run wun-smoke
```

Smoke output for `inc inc dec reset`:

```
[smoke] connected to http://localhost:8080/wun?fmt=json&caps=...
[ok] resolves=- ops=[replace@[]] state={counter: 2}
[ok] resolves=00000000 ops=[replace@[0, 0]] state={counter: 3}
[ok] resolves=00000000 ops=[replace@[0, 0]] state={counter: 4}
[ok] resolves=00000000 ops=[replace@[0, 0]] state={counter: 3}
[ok] resolves=00000000 ops=[replace@[0, 0]] state={counter: 0}
```

Initial frame is a full `:replace` at root; subsequent broadcasts
are single deep `:replace` at `[0, 0]` — the same minimal-patch
shape the cljc differ produces for the web.

## Phase 2 plan (what comes next)

- **2.B** Real SSE client + tree mirror + path-aware patch applicator
  (Swift port of `wun.diff/apply-patches`).
- **2.C** `WunComponent` registry protocol + SwiftUI renderers for
  `:wun/Stack` and `:wun/Text`.
- **2.D** Remaining foundational SwiftUI renderers (Image, Button,
  Card, Avatar, Input, List, Spacer, ScrollView).
- **2.E** Action dispatcher (POST `/intent` from iOS).
- **2.F** `:wun/WebFrame` fallback via Hotwire Native iOS — the brief's
  killer feature.
- **2.G** `X-Wun-Capabilities` header end-to-end.
- **2.H** Reference example: a user-defined Swift component shipped
  as a separate Swift package.

The brief's exit criterion for phase 2: same screens from phase 1
render natively on iOS; WebFrame fallback works at component
granularity; a developer can ship a custom native component as an
independent Swift package.
