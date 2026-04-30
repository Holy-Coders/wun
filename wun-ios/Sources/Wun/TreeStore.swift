// SwiftUI-friendly tree mirror. `TreeMirror` is the actor variant
// suitable for non-UI consumers (CLI smoke, server-side reasoning,
// future SCI integration); `TreeStore` is `@MainActor` +
// `ObservableObject` so SwiftUI views can observe changes via
// `@StateObject` / `@ObservedObject` in the usual way.
//
// Both wrap the same `Diff.apply(...)` logic; the choice between
// them is purely about isolation + reactivity.

import Foundation
import Combine

@MainActor
public final class TreeStore: ObservableObject {
    @Published public private(set) var tree: WunNode = .null
    @Published public private(set) var state: JSON = .null
    @Published public private(set) var lastResolvedIntent: String?

    /// Server-assigned connection id, echoed on every /intent POST so
    /// framework intents (`:wun/navigate`, `:wun/pop`) can be routed
    /// to *this* connection's screen-stack.
    @Published public private(set) var connID: String?

    /// Top of the screen-stack is the screen the server is currently
    /// rendering into this connection. Updated on every envelope that
    /// carries a `screen-stack` extra (initial frame, navigate, pop).
    @Published public private(set) var screenStack: [String] = []

    public init(initial: JSON = .null) {
        self.tree = WunNode.from(initial)
    }

    /// Apply an SSE envelope: patches against the tree, mirror state,
    /// pick up the server-assigned conn-id and screen-stack, remember
    /// the resolved intent id (callers may use it to drop matching
    /// pending entries in their dispatcher).
    public func apply(_ envelope: Envelope) {
        if !envelope.patches.isEmpty {
            let raw = Diff.apply(tree.toJSON(), envelope.patches)
            tree = WunNode.from(raw)
        }
        if let s = envelope.state {
            state = s
        }
        if let cid = envelope.connID {
            connID = cid
        }
        if let stack = envelope.screenStack {
            screenStack = stack
        }
        lastResolvedIntent = envelope.resolvesIntent
    }

    public func reset(to node: WunNode) {
        tree = node
    }
}
