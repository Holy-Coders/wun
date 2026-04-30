// The client's mirror of the server's UI tree. Fed by an SSE patch
// stream; rendered by SwiftUI in 2.C+. Holding state in an actor
// keeps mutations serialised across the SSE delivery thread, the
// UI thread, and any background reconciliation work.

import Foundation

public actor TreeMirror {
    public private(set) var current: JSON = .null
    public private(set) var state: JSON = .null
    public private(set) var lastResolvedIntent: String?

    public init(initial: JSON = .null) {
        self.current = initial
    }

    /// Apply an SSE envelope: patches against the tree, mirror state,
    /// remember the resolved intent id (callers may use it to drop
    /// matching pending entries in their dispatcher).
    public func apply(_ envelope: Envelope) {
        if !envelope.patches.isEmpty {
            current = Diff.apply(current, envelope.patches)
        }
        if let s = envelope.state {
            state = s
        }
        lastResolvedIntent = envelope.resolvesIntent
    }

    public func reset(to tree: JSON) {
        current = tree
    }
}
