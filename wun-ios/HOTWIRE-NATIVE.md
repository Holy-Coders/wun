# Integrating HotwireNative with Wun (iOS)

Wun's iOS package ships with a built-in `WKWebView`-based WebFrame
renderer that's good enough for development and many production
deployments. For apps that want richer behaviour -- Turbo Drive
navigation, native menu bars, Strada-style bridge components -- Wun
exposes a `WunHostNavigator` extension point that lets you swap in
[HotwireNative](https://github.com/hotwired/hotwire-native-ios) (or
any equivalent navigation system) without forking the framework.

## 1. Add the dependency

In your **host app's** `Package.swift` (NOT Wun's -- HotwireNative is
opt-in):

```swift
dependencies: [
    .package(url: "https://github.com/holy-coders/wun.git", from: "0.1.0"),
    .package(url: "https://github.com/hotwired/hotwire-native-ios", from: "1.0.0"),
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "Wun", package: "wun"),
            .product(name: "HotwireNative", package: "hotwire-native-ios"),
        ]
    )
]
```

## 2. Implement `WunHostNavigator`

```swift
import SwiftUI
import Wun
import HotwireNative

@MainActor
final class HotwireHostNavigator: WunHostNavigator {
    let session: Session

    init() {
        let config = WKWebViewConfiguration()
        // Install Wun's bridge user-script so the same intent dispatch
        // path works inside Hotwire visits.
        config.userContentController.addUserScript(WunWebBridge.userScript)
        self.session = Session(webViewConfiguration: config)
    }

    func renderWebFrame(url: URL, missing: String?) -> AnyView? {
        // Turbo Native visit. Falls back to the default WKWebView only
        // if the host hasn't been wired up yet.
        AnyView(
            HotwireVisitView(session: session, url: url)
                .frame(minHeight: 200)
        )
    }
}
```

`WunWebBridge.userScript` doesn't exist yet; the snippet sketches the
direction. The same WunBridge user-script Wun's default WKWebView
installs (see `Sources/Wun/Foundation/WebFrameRenderer.swift`) needs
to be reachable from the host so intents fired inside the Hotwire
visit reach `Wun.intentDispatcher`.

## 3. Install at startup

```swift
@main
struct MyApp: App {
    init() {
        Wun.serverBase   = URL(string: "https://api.example.com")
        Wun.hostNavigator = HotwireHostNavigator()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

## 4. Path configuration (optional)

Hotwire Native's `path-configuration.json` decides modal-vs-push per
URL pattern. Wun ships a `:present` hint per screen
(`(defscreen :foo {:present :modal ...})`); the server includes
`:presentations [...]` in every envelope. The host's `Session` delegate
can read `treeStore.topPresentation` to pick the right
`PathConfiguration` rule.

## 5. Bridge components

Hotwire's Strada lets the web component talk to the native side via a
typed message protocol. Wun's existing WunBridge already does this in
the abstract: `window.WunBridge.dispatch(intent, params)` posts to a
`WKScriptMessageHandler` named `wunDispatch`. To layer Strada on top,
register additional message handlers on the `WKUserContentController`
the navigator hands to its `Session` -- they share the
`WKWebViewConfiguration`.

## What stays in Wun

The framework still owns:

- The SSE wire (`SSEClient.swift`) and intent dispatch
  (`IntentDispatcher.swift`).
- The component vocabulary + native renderers (`Foundation/`).
- Capability negotiation -- the host doesn't decide which components
  fall back to a WebFrame; the server does.

The navigator only takes over rendering of `:wun/WebFrame` subtrees;
everything else -- including the conn-id / CSRF / theme threading
through `TreeStore` -- is unchanged.
