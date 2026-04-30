# wun-android

Kotlin library for the Wun Android client. Today it builds and runs on
the JVM (Compose Multiplatform Desktop); shipping an actual `.apk`
needs the Android Gradle Plugin which is gated by an Android SDK
install on the dev machine.

See the top-level [README](../README.md) for the project brief.

## Build / test / run

```bash
gradle build       # compile + run all unit tests
gradle test        # tests only
gradle run         # Compose Desktop demo window
```

To run the CLI smoke instead of the demo window:

```bash
gradle run -PmainClass=wun.SmokeKt
```

## Status

Phase 3.A through 3.C landed. Phase 3.D / 3.E (real Android target +
WebView WebFrame) require an Android SDK install; documented below
but not built today.

| type / object       | role                                                            |
|---------------------|-----------------------------------------------------------------|
| `WunNode`           | Sealed class for the Hiccup-shaped tree.                        |
| `Patch`             | One `replace`/`insert`/`remove` op against a child path.        |
| `Envelope`          | The SSE patch envelope, decodable via kotlinx.serialization.    |
| `Diff`              | Pure path-aware patch applicator.                                |
| `TreeMirror`        | Synchronised mirror of the server's tree + state.               |
| `SSEClient`         | OkHttp-sse listener decoding `event: patch` frames.             |
| `IntentDispatcher`  | OkHttp client posting JSON intents to `/intent`.                |
| `Registry`          | Open `tag -> WunComponent` registry.                            |
| `WunComponent`      | `@Composable (props, children) -> Unit` renderer.               |
| `WunView`           | Compose driver walking a `WunNode` through a `Registry`.        |
| `LocalWunRegistry`  | CompositionLocal carrying the registry through the tree.        |
| `WunFoundation`     | Bootstraps `:wun/*` Compose renderers into a `Registry`.        |
| `wun.demo.App`      | `@main` Compose Desktop window.                                 |

## Phase 3.D / 3.E — when the user has an Android SDK

The Kotlin library itself is platform-neutral; the bits that need an
Android target are:

1. **Gradle plugin swap.** Replace `kotlin("jvm")` with
   `id("com.android.library")` + `kotlin("android")`. Add an
   `android { compileSdk = 34; ... }` block. Compose stays the same
   (Compose Multiplatform Android target).
2. **WebView WebFrame** (3.E). Today's `WebFrameRenderer` is a
   styled placeholder. On Android, replace it with an
   `AndroidView { factory = { ctx -> WebView(ctx).apply { ... } } }`
   wrapping a real `android.webkit.WebView`. Expose a JS bridge via
   `webView.addJavascriptInterface(...)` for the WebFrame postMessage
   contract (see phase 6.D).
3. **App target.** A separate `wun-android-app/` Gradle module with
   `id("com.android.application")` plugin, a `MainActivity` that
   hosts `WunView` against a `TreeStore`, and `AndroidManifest.xml`.
   Drops in alongside today's `wun-android/` (library) and
   `wun-android-example/` (user-component artifact).

The brief's exit criterion for phase 3 (per the doc) is reachable
once that's wired up: same screens render natively on Android,
WebFrame fallback works, user code can ship its own renderers as a
separate Gradle artifact (3.F is already done; the artifact runs on
Compose Desktop today and would run on Android with the plugin
swap).

## What runs today

`gradle run` opens a Compose Desktop window showing the live counter
screen the wun-server emits, with `:wun/Stack`, `:wun/Text`,
`:wun/Button`, `:wun/Card`, `:wun/Input`, etc. all rendering
natively. The user-defined `:myapp/Greeting` falls back to a
`:wun/WebFrame` placeholder unless you launch the demo from
[wun-android-example](../wun-android-example), which registers
`:myapp/Greeting` alongside the foundational set.

`gradle run -PmainClass=wun.SmokeKt` runs a CLI smoke instead;
output mirrors the iOS `wun-smoke`:

```
[smoke] connected to http://localhost:8080/wun (caps via header)
[ok] resolves=- ops=[replace@[]] state={counter: 0}
[smoke] dispatched counter/inc
[ok] resolves=… ops=[replace@[1, 0]] state={counter: 1}
…
```
