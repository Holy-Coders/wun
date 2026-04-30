// Hot-cache persistence for the iOS / macOS clients. Mirror of
// `wun.web.persist`: on cold start (app relaunch) we hydrate the
// last-known confirmed-tree + state + screen-stack + meta from
// UserDefaults so the user sees the prior UI immediately rather
// than a blank canvas; the SSE stream reconciles within a few
// hundred ms.
//
// Snapshots are bounded by the `.standard` UserDefaults size budget
// (small in practice -- tens of KB). For genuinely large trees a
// future iteration could swap in a CoreData / file-cache, but this
// keeps the dependency surface zero.

import Foundation

public struct Snapshot: Codable, Sendable {
    public let tree: JSON
    public let state: JSON
    public let screenStack: [String]
    public let meta: JSON?
    public let savedAt: TimeInterval

    public init(tree: JSON, state: JSON, screenStack: [String],
                meta: JSON?, savedAt: TimeInterval) {
        self.tree = tree
        self.state = state
        self.screenStack = screenStack
        self.meta = meta
        self.savedAt = savedAt
    }
}

public enum Persistence {
    /// Snapshot age beyond which we discard rather than hydrate. 24h
    /// matches the web client's heuristic.
    public static let staleSeconds: TimeInterval = 24 * 60 * 60

    private static func key(for path: String) -> String { "wun.snapshot.\(path)" }

    /// Save synchronously; UserDefaults batches writes itself.
    public static func save(_ snap: Snapshot, path: String,
                            store: UserDefaults = .standard) {
        do {
            let data = try JSONEncoder().encode(snap)
            store.set(data, forKey: key(for: path))
        } catch {
            // Don't fail the UI loop because the cache misbehaves.
            print("[wun] persist save failed: \(error)")
        }
    }

    /// Returns the cached snapshot for `path` iff it exists and isn't
    /// stale. Returns nil otherwise.
    public static func load(path: String,
                            store: UserDefaults = .standard) -> Snapshot? {
        guard let data = store.data(forKey: key(for: path)) else { return nil }
        do {
            let snap = try JSONDecoder().decode(Snapshot.self, from: data)
            let age  = Date().timeIntervalSince1970 - snap.savedAt
            return age < staleSeconds ? snap : nil
        } catch {
            return nil
        }
    }

    public static func clear(path: String,
                             store: UserDefaults = .standard) {
        store.removeObject(forKey: key(for: path))
    }
}
