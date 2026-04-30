// SwiftUI driver for a Wun tree. Given a WunNode (typically the
// `tree` from a TreeStore) and a Registry, produce a SwiftUI view.
// Recursive: components return AnyView, which can contain further
// WunViews for their children.

import SwiftUI

public struct WunView: View {
    public let node: WunNode
    public let registry: Registry

    public init(_ node: WunNode, registry: Registry = .shared) {
        self.node = node
        self.registry = registry
    }

    public var body: some View {
        switch node {
        case .null:
            EmptyView()

        case .text(let value):
            Text(value)

        case .number(let value):
            Text(WunView.formatNumber(value))

        case .bool(let value):
            Text(String(value))

        case .opaque:
            EmptyView()

        case .component(let tag, let props, let children):
            if let render = registry.lookup(tag) {
                render(props, children)
            } else {
                Text("[unknown: \(tag)]")
                    .foregroundColor(.red)
                    .font(.system(.caption, design: .monospaced))
            }
        }
    }

    private static func formatNumber(_ n: Double) -> String {
        if n.truncatingRemainder(dividingBy: 1) == 0,
           abs(n) < Double(Int64.max) {
            return String(Int64(n))
        }
        return String(n)
    }
}
