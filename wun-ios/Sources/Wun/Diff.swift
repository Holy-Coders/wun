// Path-aware patch applicator. Swift port of the shared cljc
// `wun.diff/apply-patches` -- same Hiccup-aware indexing, same
// :replace / :insert / :remove ops, same path semantics.
//
// Path elements are integer child indices into the *children* of a
// component vector, skipping the tag (index 0) and the optional
// props map (index 1). `[]` addresses the whole tree. The producer
// (server) and the consumer (this) live in different languages but
// implement the same algorithm; the cljc and the Swift versions
// must stay step for step.

import Foundation

public enum Diff {
    // MARK: - Hiccup helpers

    static func hasProps(_ array: [JSON]) -> Bool {
        guard array.count >= 2 else { return false }
        if case .object = array[1] { return true }
        return false
    }

    static func childOffset(_ array: [JSON]) -> Int {
        hasProps(array) ? 2 : 1
    }

    // MARK: - Public apply

    /// Apply a single patch to a tree, returning a new tree.
    public static func apply(_ tree: JSON, _ patch: Patch) -> JSON {
        switch patch.op {
        case .replace:
            return replace(tree, path: patch.path, value: patch.value ?? .null)
        case .insert:
            return insert(tree, path: patch.path, value: patch.value ?? .null)
        case .remove:
            return remove(tree, path: patch.path)
        }
    }

    /// Sequentially fold a list of patches over a tree.
    public static func apply(_ tree: JSON, _ patches: [Patch]) -> JSON {
        patches.reduce(tree) { apply($0, $1) }
    }

    // MARK: - Op implementations

    static func replace(_ tree: JSON, path: [Int], value: JSON) -> JSON {
        if path.isEmpty { return value }
        let parent = Array(path.dropLast())
        let idx = path.last!
        return updateAtPath(tree, path: parent) { replaceChild($0, at: idx, with: value) }
    }

    static func insert(_ tree: JSON, path: [Int], value: JSON) -> JSON {
        precondition(!path.isEmpty, "insert requires non-empty path")
        let parent = Array(path.dropLast())
        let idx = path.last!
        return updateAtPath(tree, path: parent) { insertChild($0, at: idx, value: value) }
    }

    static func remove(_ tree: JSON, path: [Int]) -> JSON {
        precondition(!path.isEmpty, "remove requires non-empty path")
        let parent = Array(path.dropLast())
        let idx = path.last!
        return updateAtPath(tree, path: parent) { removeChild($0, at: idx) }
    }

    // MARK: - Path navigation

    static func updateAtPath(_ tree: JSON,
                             path: [Int],
                             _ fn: (JSON) -> JSON) -> JSON {
        if path.isEmpty { return fn(tree) }
        guard case .array(var array) = tree else { return tree }
        let offset = childOffset(array)
        let head = path[0]
        let rest = Array(path.dropFirst())
        let absIdx = head + offset
        guard absIdx < array.count else { return tree }
        array[absIdx] = updateAtPath(array[absIdx], path: rest, fn)
        return .array(array)
    }

    // MARK: - Child mutators

    static func replaceChild(_ tree: JSON, at i: Int, with value: JSON) -> JSON {
        guard case .array(var array) = tree else { return tree }
        let absIdx = i + childOffset(array)
        guard absIdx < array.count else { return tree }
        array[absIdx] = value
        return .array(array)
    }

    static func insertChild(_ tree: JSON, at i: Int, value: JSON) -> JSON {
        guard case .array(var array) = tree else { return tree }
        let absIdx = min(i + childOffset(array), array.count)
        array.insert(value, at: absIdx)
        return .array(array)
    }

    static func removeChild(_ tree: JSON, at i: Int) -> JSON {
        guard case .array(var array) = tree else { return tree }
        let absIdx = i + childOffset(array)
        guard absIdx < array.count else { return tree }
        array.remove(at: absIdx)
        return .array(array)
    }
}
