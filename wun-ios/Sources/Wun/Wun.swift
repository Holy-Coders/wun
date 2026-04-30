// Phase 2.A scaffold. Real SSE client + tree mirror land in 2.B; the
// component registry + SwiftUI renderers in 2.C. Today this module
// exposes only the wire-shape types (JSON, WunNode, Patch, Envelope)
// so a host app or test can decode envelopes received over HTTP.
//
// See ../../README.md and the project brief at the repo root.

import Foundation

public enum Wun {
    public static let version = "0.1.0-phase2a"
    public static let supportedOps: Set<PatchOp> = [.replace, .insert, .remove]
}
