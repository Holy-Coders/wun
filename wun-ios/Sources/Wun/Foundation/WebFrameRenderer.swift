// SwiftUI renderer for `:wun/WebFrame`. The brief calls this the
// killer feature: any component the native client lacks falls back
// to a Turbo / WebKit frame pointing at a server-rendered page.
//
// 6.D adds the native bridge: clicking a button inside a WebFrame
// fires an intent through `Wun.intentDispatcher` (i.e. through the
// app's native IntentDispatcher and SSE) rather than POSTing to
// /intent over a fresh HTTP connection. The wiring uses a
// WKScriptMessageHandler registered as `wunDispatch`; the server's
// HTML stub calls `window.WunBridge.dispatch(intent, params)`, the
// bridge forwards to the message handler, and the app routes it.
//
// Phase 2.F+ may swap the WKWebView for Hotwire Native iOS for
// proper Turbo navigation; the renderer surface stays the same.

import SwiftUI
import WebKit

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

public enum WunWebFrame {
    public static let render: WunComponent = { props, _ in
        let missing = props["missing"]?.stringValue
        let src     = props["src"]?.stringValue

        guard let src, let resolved = resolveURL(src) else {
            return AnyView(WunWebFrame.diagnostic(missing: missing))
        }
        return AnyView(WunWebView(url: resolved).frame(minHeight: 200))
    }

    private static func resolveURL(_ src: String) -> URL? {
        if let abs = URL(string: src), abs.scheme != nil { return abs }
        guard let base = Wun.serverBase else { return nil }
        return URL(string: src, relativeTo: base)
    }

    private static func diagnostic(missing: String?) -> some View {
        let label = missing.map { "[WebFrame: missing renderer for \($0); no src]" }
                          ?? "[WebFrame: no src and no Wun.serverBase set]"
        return SwiftUI.Text(label)
            .foregroundColor(.red)
            .font(.system(.caption, design: .monospaced))
            .padding(8)
    }
}

// MARK: - SwiftUI / WKWebView bridge (works on iOS and macOS).

public struct WunWebView: View {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    public var body: some View {
        WunWebViewRepresentable(url: url)
    }
}

// MARK: - WunBridge user-script + message handler

private let wunBridgeUserScript: String = """
window.WunBridge = window.WunBridge || {
  dispatch: function(intent, params) {
    try {
      window.webkit.messageHandlers.wunDispatch.postMessage({
        intent: String(intent),
        params: params || {}
      });
    } catch (e) {
      console.error('WunBridge.dispatch failed', e);
    }
  }
};
"""

@MainActor
final class WunBridgeHandler: NSObject, WKScriptMessageHandler {
    func userContentController(_ uc: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == "wunDispatch",
              let dict = message.body as? [String: Any],
              let intent = dict["intent"] as? String
        else { return }
        let raw = dict["params"] as? [String: Any] ?? [:]
        let params = raw.reduce(into: [String: JSON]()) { acc, kv in
            acc[kv.key] = jsonFromAny(kv.value)
        }
        Wun.intentDispatcher(intent, params)
    }
}

private func jsonFromAny(_ v: Any) -> JSON {
    if v is NSNull { return .null }
    if let b = v as? Bool, "\(type(of: v))" == "__NSCFBoolean" { return .bool(b) }
    if let s = v as? String { return .string(s) }
    if let i = v as? Int    { return .int(Int64(i)) }
    if let d = v as? Double { return .double(d) }
    if let arr = v as? [Any] {
        return .array(arr.map(jsonFromAny))
    }
    if let dict = v as? [String: Any] {
        return .object(dict.reduce(into: [String: JSON]()) { acc, kv in
            acc[kv.key] = jsonFromAny(kv.value)
        })
    }
    return .string("\(v)")
}

@MainActor
private func makeBridgedWebView() -> WKWebView {
    let userScript = WKUserScript(
        source: wunBridgeUserScript,
        injectionTime: .atDocumentStart,
        forMainFrameOnly: true
    )
    let userContent = WKUserContentController()
    userContent.addUserScript(userScript)
    let handler = WunBridgeHandler()
    userContent.add(handler, name: "wunDispatch")

    let config = WKWebViewConfiguration()
    config.userContentController = userContent
    return WKWebView(frame: .zero, configuration: config)
}

#if canImport(UIKit)
private struct WunWebViewRepresentable: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = makeBridgedWebView()
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        if view.url != url {
            view.load(URLRequest(url: url))
        }
    }
}
#elseif canImport(AppKit)
private struct WunWebViewRepresentable: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> WKWebView {
        let view = makeBridgedWebView()
        view.load(URLRequest(url: url))
        return view
    }

    func updateNSView(_ view: WKWebView, context: Context) {
        if view.url != url {
            view.load(URLRequest(url: url))
        }
    }
}
#endif
