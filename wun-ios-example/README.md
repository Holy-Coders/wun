# wun-ios-example

A separate Swift package demonstrating that user code participates
in the Wun registry identically to framework code. Ships:

- **`WunExample`** library — registers `:myapp/Greeting`
  (a small "hello" component) into a `Wun.Registry` alongside whatever
  `:wun/*` renderers the host app has wired up.
- **`example-smoke`** CLI — connects to a running wun-server,
  advertises the foundational set + `:myapp/Greeting` via
  `X-Wun-Capabilities`, fires a few intents, prints one line per
  envelope.
- **`wun-demo-mac`** SwiftUI macOS app — opens a window and renders
  the live counter screen on screen, with `:myapp/Greeting` natively
  rendered (not as a WebFrame fallback).

## Run the on-screen demo

In one terminal:

```bash
cd ../wun-server
clojure -M:run
```

In another, either:

```bash
cd wun-ios-example
swift run wun-demo-mac
```

…or open `wun-ios-example/Package.swift` in Xcode, pick the
`wun-demo-mac` scheme, ⌘R. A native macOS window appears showing
the counter screen the server emits. Click the buttons; counter
updates after the SSE round-trip (no optimistic UI on native --
the brief says that lands in phase 4 alongside SCI).

## What the demo wires together

```swift
// AppViewModel.swift
WunFoundation.register(into: registry)   // :wun/* SwiftUI renderers
WunExample.register(into: registry)       // :myapp/Greeting
dispatcher.install()                       // Wun.intentDispatcher
                                           // -> POST /intent
SSEClient(url: ..., headers: [
    "X-Wun-Capabilities": <12 caps>,
    "X-Wun-Format": "json",
])
```

Server log on connect:

```
caps={:myapp/Greeting 1, :wun/Stack 1, :wun/Text 1, ...}
```

A peer client without `:myapp/Greeting` in its caps would see the
Greeting subtree collapsed to `[:wun/WebFrame {:missing
:myapp/Greeting :src "/web-frames/myapp%2FGreeting"}]` while the
rest of the tree stays native -- demonstrating capability
negotiation at the smallest containing subtree.

## Layout

```
wun-ios-example/
├── Package.swift                          # depends on Wun by path
└── Sources/
    ├── WunExample/                        # the user library
    │   ├── WunExample.swift               # register(into:)
    │   └── GreetingRenderer.swift         # SwiftUI for :myapp/Greeting
    ├── ExampleSmoke/                      # CLI smoke (no UI)
    │   └── main.swift
    └── WunDemoMac/                        # SwiftUI macOS demo
        ├── WunDemoApp.swift               # @main App + WindowGroup
        ├── ContentView.swift              # status bar + WunView
        └── AppViewModel.swift             # SSE / dispatcher / registry wiring
```
