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

    /// Page meta from the server's `:meta` extra. Title is the most
    /// commonly read field (NavigationView title); `meta` keeps the
    /// rest available for whoever wants it.
    @Published public private(set) var title: String?
    @Published public private(set) var meta: JSON?

    public init(initial: JSON = .null) {
        self.tree = WunNode.from(initial)
    }

    /// Apply an SSE envelope: patches against the tree, mirror state,
    /// pick up the server-assigned conn-id, screen-stack, and meta,
    /// remember the resolved intent id (callers may use it to drop
    /// matching pending entries in their dispatcher).
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
        if let m = envelope.meta {
            meta = m
            if let dict = m.objectValue,
               case .string(let t) = dict["title"] ?? .null {
                title = t
            }
        }
        lastResolvedIntent = envelope.resolvesIntent
    }

    public func reset(to node: WunNode) {
        tree = node
    }

    /// Hydrate from a previously-saved snapshot (cold start). Does NOT
    /// publish through `apply(_:)`; the next server envelope will
    /// reconcile any drift.
    public func hydrate(tree: WunNode, state: JSON, screenStack: [String], meta: JSON?) {
        self.tree = tree
        self.state = state
        self.screenStack = screenStack
        if let m = meta {
            self.meta = m
            if let dict = m.objectValue,
               case .string(let t) = dict["title"] ?? .null {
                self.title = t
            }
        }
    }
}
