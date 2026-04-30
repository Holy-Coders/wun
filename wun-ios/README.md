# wun-ios

Swift package for the Wun iOS client. See the top-level
[README](../README.md) for the project brief.

## Build / test

```bash
swift build
swift test
```

## Phase 2.A status

**Wire-shape types only.** No SSE client, no renderer, no networking.
The package exposes Codable/Equatable models that decode the JSON
envelope a phase-2-aware server emits when asked for
`Accept: application/json` (or queried with `?fmt=json` on `/wun`).

| type        | role                                                             |
|-------------|------------------------------------------------------------------|
| `JSON`      | Closed recursive variant covering JSON's six shapes.             |
| `WunNode`   | Hiccup-shaped tree node (text / number / bool / component).      |
| `Patch`     | One `:replace`/`:insert`/`:remove` op against a child path.      |
| `Envelope`  | The SSE patch envelope: `patches`, `status`, `state`, `resolves-intent`. |

Tests cover decoding a real envelope from the running server plus a
`WunNode.from(json).toJSON()` round-trip.

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
