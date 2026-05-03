// Host-app navigation abstraction. The default WebFrame renderer
// loads a URL into a plain WKWebView with the WunBridge user-script
// installed. Apps that want richer Web↔native plumbing -- e.g.
// HotwireNative, Turbo Native, a custom Strada bridge -- implement
// `WunHostNavigator` and assign it to `Wun.hostNavigator`. The
// WebFrameRenderer asks the host first; if it answers nil, the
// renderer falls back to its default WKWebView path.
//
// Why a protocol instead of hard-coding HotwireNative: the package
// is opt-in. Linking it pulls in Turbo, dependency, App-Transport-
// Security configuration concerns, etc. Keeping it host-side means
// Wun ships with one fewer transitive dep and apps that don't use
// HotwireNative aren't paying for it.
//
// Suggested host implementation pattern (HotwireNative shown):
//
//   import HotwireNative
//
//   final class HotwireHostNavigator: WunHostNavigator {
//     let session: Session
//     init() {
//       self.session = Session(...)
//       session.delegate = self
//     }
//     func renderWebFrame(url: URL, missing: String?) -> AnyView? {
//       // Trigger a Turbo visit on the host's existing session, OR
//       // return a SwiftUI view that hosts a Hotwire visit.
//       AnyView(HotwireVisit(session: session, url: url))
//     }
//   }
//   Wun.hostNavigator = HotwireHostNavigator()
//
// Wun.serverBase still resolves the relative `:src` Web-Frame URLs;
// the navigator only takes over the rendering side.

import SwiftUI

@MainActor
public protocol WunHostNavigator {
    /// Render a SwiftUI view for `url` (resolved + ready to load).
    /// Return nil to let the default WKWebView fallback render.
    func renderWebFrame(url: URL, missing: String?) -> AnyView?
}

extension Wun {
    /// Host-supplied navigator that can take over `:wun/WebFrame`
    /// rendering. Defaults to nil; the WebFrameRenderer then uses its
    /// built-in WKWebView + WunBridge implementation.
    @MainActor public static var hostNavigator: WunHostNavigator? = nil
}
