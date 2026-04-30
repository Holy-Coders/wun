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

    func testFoundationRegistersAllFoundationalComponents() {
        let r = Registry()
        WunFoundation.register(into: r)
        let expected = [
            "wun/Stack", "wun/Text", "wun/Image", "wun/Button",
            "wun/Card", "wun/Avatar", "wun/Input", "wun/List",
            "wun/Spacer", "wun/ScrollView", "wun/WebFrame",
        ]
        for tag in expected {
            XCTAssertNotNil(r.lookup(tag), "\(tag) should be registered")
        }
    }

    /// Renderers are `(props, children) -> AnyView`. We can't easily
    /// assert what the resulting SwiftUI view tree *looks like* without
    /// pulling in a heavyweight view-inspector dep, but we can verify
    /// every foundational renderer constructs without crashing on a
    /// representative payload, which catches obvious type errors in
    /// the SwiftUI expressions.
    func testFoundationalRenderersDoNotCrash() {
        _ = WunStack.render([:],                                  [])
        _ = WunStack.render(["direction": .string("row")],        [])
        _ = WunText.render([:],                                   [.text("hello")])
        _ = WunText.render(["variant": .string("h1")],            [.text("Counter: 1")])
        _ = WunImage.render(["src": .string("https://example.com/x.png")], [])
        _ = WunButton.render(
                ["on-press": .object(["intent": .string("counter/inc"),
                                      "params": .object([:])])],
                [.text("+")])
        _ = WunCard.render(["title": .string("Hi")], [.text("body")])
        _ = WunAvatar.render(["initials": .string("AT"), "size": .int(48)], [])
        _ = WunInput.render(["value": .string(""), "placeholder": .string("type")], [])
        _ = WunList.render([:],                                   [.text("a"), .text("b")])
        _ = WunSpacer.render([:],                                 [])
        _ = WunSpacer.render(["size": .int(16)],                  [])
        _ = WunScrollView.render([:],                             [.text("scrolled")])
        _ = WunScrollView.render(["direction": .string("horizontal")], [])
        _ = WunWebFrame.render(["src":     .string("https://example.com/x"),
                                "missing": .string("wun/Card")], [])
        _ = WunWebFrame.render([:],                               [])  // diagnostic path
    }
}
