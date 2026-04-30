# wun-android

Kotlin/JVM library for the Wun Android client. See the top-level
[README](../README.md) for the project brief.

## Build / test

```bash
gradle build       # compile + run all unit tests
gradle test        # tests only
gradle run         # end-to-end smoke against localhost:8080
```

## Status

Phase 3.A + 3.B landed. The library does the full wire loop: SSE
client connecting with `X-Wun-Capabilities` headers, JSON envelope
decoding, path-aware patch application into a `TreeMirror`, intent
dispatch with proper 400 error handling. Compose UI renderers are
3.C; no on-screen rendering yet.

| type / object       | role                                                            |
|---------------------|-----------------------------------------------------------------|
| `WunNode`           | Sealed class for the Hiccup-shaped tree (Text / Number / Bool / Component / Null / Opaque). |
| `Patch`             | One `replace`/`insert`/`remove` op against a child path.        |
| `Envelope`          | The SSE patch envelope, decodable from JSON via kotlinx.serialization. |
| `Diff`              | Pure path-aware patch applicator (Kotlin port of `wun.diff`).   |
| `TreeMirror`        | Synchronised mirror of the server's tree + state.               |
| `SSEClient`         | OkHttp-sse listener decoding `event: patch` frames.             |
| `IntentDispatcher`  | OkHttp client posting JSON intents to `/intent`.                |
| `Registry`          | Open `tag -> renderer` registry (renderer signature firms up in 3.C). |

## What runs

`gradle run` against the live server emits:

```
[smoke] connected to http://localhost:8080/wun (caps via header)
[ok] resolves=- ops=[replace@[]] state={counter: 0}
[smoke] dispatched counter/inc
[ok] resolves=… ops=[replace@[1, 0]] state={counter: 1}
[smoke] dispatched counter/by 5
[ok] resolves=… ops=[replace@[1, 0]] state={counter: 6}
[smoke] dispatched counter/by 'oops' (should 400)
[err] intent=counter/by status=400 error={"explanation":{"n":["should be an integer"]}}
[smoke] dispatched counter/reset
[ok] resolves=… ops=[replace@[1, 0]] state={counter: 0}
```

Each broadcast a single `replace@[1, 0]` patch (the counter text
inside `:wun/Text`); the user-defined `:myapp/Greeting` falls back
to a `:wun/WebFrame` because this smoke advertises the foundational
`:wun/*` set without it.

## Phase 3 plan (what comes next)

- **3.C** Compose Multiplatform Desktop renderer for the
  foundational vocabulary; equivalent of the SwiftUI `WunFoundation`
  bundle. A small Compose for Desktop window opens and renders the
  live counter screen.
- **3.D** Real Android app target -- adds the Android Gradle Plugin,
  Compose Android, an actual APK that runs on a device or emulator.
- **3.E** WebView-based `:wun/WebFrame` fallback. The server's
  `/web-frames/<key>/<token>` endpoint is the same one iOS hits via
  WKWebView; Android uses `WebView`.
- **3.F** Reference user component as a separate Gradle artifact,
  parallel to wun-ios-example.
