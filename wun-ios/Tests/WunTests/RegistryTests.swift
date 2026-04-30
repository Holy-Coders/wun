import XCTest
import SwiftUI
@testable import Wun

final class RegistryTests: XCTestCase {
    func testRegisterAndLookup() {
        let r = Registry()
        XCTAssertNil(r.lookup("wun/Stack"))
        r.register("wun/Stack") { _, _ in AnyView(EmptyView()) }
        XCTAssertNotNil(r.lookup("wun/Stack"))
    }

    func testRegisteredKeysAreSorted() {
        let r = Registry()
        r.register("wun/Text",   { _, _ in AnyView(EmptyView()) })
        r.register("wun/Stack",  { _, _ in AnyView(EmptyView()) })
        r.register("myapp/Card", { _, _ in AnyView(EmptyView()) })
        XCTAssertEqual(r.registered(), ["myapp/Card", "wun/Stack", "wun/Text"])
    }

    func testFoundationRegistersStackAndText() {
        let r = Registry()
        WunFoundation.register(into: r)
        XCTAssertNotNil(r.lookup("wun/Stack"))
        XCTAssertNotNil(r.lookup("wun/Text"))
        XCTAssertNil(r.lookup("wun/Button"),
                     "phase 2.D adds Button, Image, Card, ...; today they're not registered")
    }

    /// Renderers are `(props, children) -> AnyView`. We can't easily
    /// assert what the resulting SwiftUI view tree *looks like* without
    /// pulling in a heavyweight view-inspector dep, but we can verify
    /// the renderer doesn't crash on an empty payload, which is enough
    /// to catch obvious type errors in the Stack/Text expressions.
    func testFoundationalRenderersDoNotCrash() {
        _ = WunStack.render([:],                                  [])
        _ = WunStack.render(["direction": .string("row")],        [])
        _ = WunStack.render(["gap": .int(8), "padding": .int(12)], [.text("hi")])
        _ = WunText.render([:],                                   [.text("hello")])
        _ = WunText.render(["variant": .string("h1")],            [.text("Counter: 1")])
    }
}
