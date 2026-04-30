// Top-level Wun namespace + global intent dispatcher hook.
//
// `intentDispatcher` is the closure that foundational renderers
// (Button, Input, etc.) call when the user fires an intent. It's a
// `@MainActor` global because there's only ever one wire connection
// per process, and the alternative -- threading the dispatcher
// through SwiftUI Environment all the way down -- would inflate
// every renderer's signature for negligible win. Phase 2.E plugs in
// a real implementation that POSTs JSON to /intent.

import Foundation

public enum Wun {
    public static let version = "0.1.0-phase2f"
    public static let supportedOps: Set<PatchOp> = [.replace, .insert, .remove]

    /// Called by Button/Input/etc. when an intent is fired in the UI.
    /// Set this from your host app at startup. Defaults to a no-op
    /// that prints a warning so missing wiring is visible in dev.
    public static var intentDispatcher: @MainActor (
        _ intent: String,
        _ params: [String: JSON]
    ) -> Void = { intent, _ in
        print("[wun] no intent dispatcher set; dropped \(intent)")
    }

    /// Server origin used for resolving relative URLs in `:wun/WebFrame`
    /// payloads. The server emits relative `:src` paths
    /// (e.g. "/web-frames/wun%2FCard"); the WebFrame renderer joins
    /// them against this base.
    public static var serverBase: URL?
}
