import XCTest
@testable import Wun

final class DiffTests: XCTestCase {
    // Helpers to build trees concisely.
    func arr(_ values: JSON...) -> JSON   { .array(values) }
    func obj(_ pairs: (String, JSON)...) -> JSON {
        var m: [String: JSON] = [:]
        for (k, v) in pairs { m[k] = v }
        return .object(m)
    }
    func patch(_ op: PatchOp, path: [Int], value: JSON? = nil,
               order: [ChildOrderEntry]? = nil) -> Patch {
        Patch(op: op, path: path, value: value, order: order)
    }
    func entry(key: String, existing: Bool = true, value: JSON? = nil) -> ChildOrderEntry {
        ChildOrderEntry(key: .string(key), existing: existing, value: value)
    }

    // MARK: - replace at root

    func testReplaceAtRoot() {
        let before: JSON = .string("old")
        let after  = Diff.apply(before, patch(.replace, path: [], value: .string("new")))
        XCTAssertEqual(after, .string("new"))
    }

    // MARK: - deep replace at [0,0]

    /// `[:wun/Text {:variant :h1} "Counter: 0"]` -> "Counter: 1" via path [0].
    /// That replaces child 0 of the Text component, which is the text leaf.
    func testReplaceTextLeaf() {
        let before = arr(.string("wun/Text"), obj(("variant", .string("h1"))), .string("Counter: 0"))
        let after  = Diff.apply(before, patch(.replace, path: [0], value: .string("Counter: 1")))
        XCTAssertEqual(after,
                       arr(.string("wun/Text"), obj(("variant", .string("h1"))), .string("Counter: 1")))
    }

    /// Nested case: `[:wun/Stack {} [:wun/Text {} "x"] [:wun/Button {} "+"]]`.
    /// Path [0,0] = first child of first child = the "x" text.
    func testReplaceNested() {
        let before = arr(
            .string("wun/Stack"), .object([:]),
            arr(.string("wun/Text"),   .object([:]), .string("x")),
            arr(.string("wun/Button"), .object([:]), .string("+"))
        )
        let after = Diff.apply(before, patch(.replace, path: [0, 0], value: .string("y")))
        XCTAssertEqual(after, arr(
            .string("wun/Stack"), .object([:]),
            arr(.string("wun/Text"),   .object([:]), .string("y")),
            arr(.string("wun/Button"), .object([:]), .string("+"))
        ))
    }

    // MARK: - insert at end

    func testInsertAtEnd() {
        let before = arr(.string("wun/Stack"), .object([:]),
                         .string("a"), .string("b"))
        let after  = Diff.apply(before, patch(.insert, path: [2], value: .string("c")))
        XCTAssertEqual(after,
                       arr(.string("wun/Stack"), .object([:]),
                           .string("a"), .string("b"), .string("c")))
    }

    // MARK: - remove from end (highest first preserves indices)

    func testRemoveTrailing() {
        let before = arr(.string("wun/Stack"), .object([:]),
                         .string("a"), .string("b"), .string("c"), .string("d"))
        // The cljc differ emits remove patches highest-index-first
        // for trailing removals; apply them in that order.
        let patches = [patch(.remove, path: [3]), patch(.remove, path: [2])]
        let after   = Diff.apply(before, patches)
        XCTAssertEqual(after,
                       arr(.string("wun/Stack"), .object([:]),
                           .string("a"), .string("b")))
    }

    // MARK: - no props branch

    /// A Hiccup vector without a props map: `[:tag a b c]`. Child 0 is `a`.
    func testNoPropsIndexing() {
        let before = arr(.string("wun/Stack"), .string("a"), .string("b"))
        let after  = Diff.apply(before, patch(.replace, path: [1], value: .string("B")))
        XCTAssertEqual(after, arr(.string("wun/Stack"), .string("a"), .string("B")))
    }

    // MARK: - apply many

    func testApplyMany() {
        let before: JSON = .null
        let value = arr(.string("wun/Stack"), .object([:]), .string("hi"))
        let after = Diff.apply(before, [
            patch(.replace, path: [], value: value),
            patch(.replace, path: [0], value: .string("bye")),
        ])
        XCTAssertEqual(after, arr(.string("wun/Stack"), .object([:]), .string("bye")))
    }

    // MARK: - wire v2: keyed children

    /// Apply a `:children` op at the root that reorders existing keyed children.
    func testChildrenOpReordersExisting() {
        let a = arr(.string("wun/Text"), obj(("key", .string("a"))), .string("A"))
        let b = arr(.string("wun/Text"), obj(("key", .string("b"))), .string("B"))
        let c = arr(.string("wun/Text"), obj(("key", .string("c"))), .string("C"))
        let before = arr(.string("wun/Stack"), .object([:]), a, b, c)
        let after = Diff.apply(before,
            patch(.children, path: [],
                  order: [entry(key: "c"), entry(key: "a"), entry(key: "b")]))
        XCTAssertEqual(after, arr(.string("wun/Stack"), .object([:]), c, a, b))
    }

    /// `:children` carrying a fresh subtree for a key not present in old.
    func testChildrenOpInsertsInline() {
        let a = arr(.string("wun/Text"), obj(("key", .string("a"))), .string("A"))
        let bNew = arr(.string("wun/Text"), obj(("key", .string("b"))), .string("B!"))
        let before = arr(.string("wun/Stack"), .object([:]), a)
        let after = Diff.apply(before,
            patch(.children, path: [],
                  order: [entry(key: "a"),
                          entry(key: "b", existing: false, value: bNew)]))
        XCTAssertEqual(after, arr(.string("wun/Stack"), .object([:]), a, bNew))
    }

    /// Ordering followed by a recursive prop change at the new index.
    func testChildrenOpThenRecursiveReplace() {
        let a1 = arr(.string("wun/Text"), obj(("key", .string("a"))), .string("A1"))
        let a2 = arr(.string("wun/Text"), obj(("key", .string("a"))), .string("A2"))
        let b  = arr(.string("wun/Text"), obj(("key", .string("b"))), .string("B"))
        let before = arr(.string("wun/Stack"), .object([:]), a1, b)
        // After :children at root, the order is [b, a1]. Then path [1, 0]
        // replaces the text leaf of the now-second child (a1) with "A2".
        let after = Diff.apply(before, [
            patch(.children, path: [],
                  order: [entry(key: "b"), entry(key: "a")]),
            patch(.replace, path: [1, 0], value: .string("A2")),
        ])
        XCTAssertEqual(after, arr(.string("wun/Stack"), .object([:]), b, a2))
    }
}
