// SwiftUI renderer for `:wun/WebFrame`. The brief calls this the
// killer feature: any component the native client lacks falls back
// to a Turbo / WebKit frame pointing at a server-rendered page.
//
// Today this is a plain WKWebView showing whatever HTML the server
// returns for the WebFrame's `:src`. Phase 2.F+ swaps in Hotwire
// Native iOS (https://github.com/hotwired/hotwire-native-ios) for
// proper Turbo navigation handling; the renderer surface stays the
// same.

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

#if canImport(UIKit)
private struct WunWebViewRepresentable: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
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
        let view = WKWebView()
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
