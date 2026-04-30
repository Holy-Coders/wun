// SwiftUI driver for a Wun tree. Given a WunNode (typically the
// `tree` from a TreeStore), produce a SwiftUI view by dispatching
// each component keyword through a `Registry`. Recursive: components
// return AnyView, which can contain further WunViews for their
// children.
//
// The registry is read from the SwiftUI Environment (default
// `Registry.shared`). That way a renderer like `WunStack` -- which
// builds children with `WunView(kid)` and no explicit registry --
// still picks up the same registry the host wired up at the top of
// the view tree. Override per-tree with
//   `.environment(\.wunRegistry, myRegistry)`
// or pass `registry:` directly on a single WunView.

import SwiftUI

public struct WunView: View {
    public let node: WunNode
    private let explicitRegistry: Registry?

    @Environment(\.wunRegistry) private var environmentRegistry

    public init(_ node: WunNode, registry: Registry? = nil) {
        self.node = node
        self.explicitRegistry = registry
    }

    public var body: some View {
        let registry = explicitRegistry ?? environmentRegistry

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

// MARK: - Environment plumbing

private struct WunRegistryKey: EnvironmentKey {
    static let defaultValue: Registry = .shared
}

public extension EnvironmentValues {
    /// The Wun renderer registry that descendant `WunView`s use to
    /// resolve component keywords. Defaults to `Registry.shared`.
    var wunRegistry: Registry {
        get { self[WunRegistryKey.self] }
        set { self[WunRegistryKey.self] = newValue }
    }
}
