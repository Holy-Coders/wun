import XCTest
@testable import Wun

final class EnvelopeTests: XCTestCase {
    /// Sample taken verbatim from a curl `Accept: application/json`
    /// against the running phase-2.A server, then trimmed to keep the
    /// fixture readable. Anything we add here breaks the build the
    /// moment the server's wire shape drifts.
    static let initialFrame: String = """
    {
      "patches": [
        {
          "op": "replace",
          "path": [],
          "value": [
            "wun/Stack",
            {"gap": 12, "padding": 24},
            ["wun/Text", {"variant": "h1"}, "Counter: 0"],
            ["wun/Stack", {"direction": "row", "gap": 8},
              ["wun/Button", {"on-press": {"intent": "counter/dec", "params": {}}}, "-"],
              ["wun/Button", {"on-press": {"intent": "counter/inc", "params": {}}}, "+"]
            ]
          ]
        }
      ],
      "status": "ok",
      "state":  {"counter": 0},
      "resolves-intent": null
    }
    """

    func testDecodeInitialFrame() throws {
        let env = try Envelope.decode(Self.initialFrame)
        XCTAssertEqual(env.status, "ok")
        XCTAssertEqual(env.patches.count, 1)
        XCTAssertEqual(env.patches[0].op, .replace)
        XCTAssertEqual(env.patches[0].path, [])
        XCTAssertNil(env.resolvesIntent)
        XCTAssertEqual(env.state, .object(["counter": .int(0)]))
    }

    func testWunNodeShape() throws {
        let env = try Envelope.decode(Self.initialFrame)
        guard let value = env.patches[0].value else {
            return XCTFail("expected patch value")
        }
        let root = WunNode.from(value)
        guard case let .component(tag, props, children) = root else {
            return XCTFail("expected root component")
        }
        XCTAssertEqual(tag, "wun/Stack")
        XCTAssertEqual(props["gap"], .int(12))
        XCTAssertEqual(props["padding"], .int(24))
        XCTAssertEqual(children.count, 2)

        guard case let .component(textTag, textProps, textKids) = children[0] else {
            return XCTFail("expected first child to be Text")
        }
        XCTAssertEqual(textTag, "wun/Text")
        XCTAssertEqual(textProps["variant"], .string("h1"))
        XCTAssertEqual(textKids, [.text("Counter: 0")])

        guard case let .component(rowTag, _, rowKids) = children[1] else {
            return XCTFail("expected second child to be Stack")
        }
        XCTAssertEqual(rowTag, "wun/Stack")
        XCTAssertEqual(rowKids.count, 2)
    }

    func testRoundTripThroughJSON() throws {
        let env = try Envelope.decode(Self.initialFrame)
        guard let raw = env.patches[0].value else {
            return XCTFail("expected patch value")
        }
        // WunNode.from(JSON) followed by WunNode.toJSON() should be
        // round-trippable for trees without opaque or null leaves.
        let node = WunNode.from(raw)
        XCTAssertEqual(node.toJSON(), raw)
    }
}
