// Open component registry. Each component keyword (a namespaced
// string like "wun/Stack" or "myapp/RichEditor") maps to a renderer
// function that takes (props, children) and returns SwiftUI Views.
// Framework code and user code register through this same API --
// the registry doesn't distinguish the two, mirroring the cljc
// `wun.components/registry` on the server.

import Foundation
import SwiftUI

/// Renderer signature: given the component's props and already-
/// materialised child WunNodes, produce a SwiftUI view. AnyView is
/// the type-erased return because each component picks its own
/// concrete View; the registry value space has to be uniform.
public typealias WunComponent = (
    _ props: [String: JSON],
    _ children: [WunNode]
) -> AnyView

public final class Registry: @unchecked Sendable {
    private let lock = NSLock()
    private var components: [String: WunComponent] = [:]

    public init() {}

    /// Process-wide default registry. Foundation.register(into:) is
    /// usually called against this; user code can mix in additional
    /// components alongside `:wun/*` without needing its own
    /// instance, but a host can also keep multiple registries
    /// (e.g. for testing or for a sandboxed sub-tree).
    public static let shared = Registry()

    public func register(_ tag: String, _ render: @escaping WunComponent) {
        lock.lock(); defer { lock.unlock() }
        components[tag] = render
    }

    public func lookup(_ tag: String) -> WunComponent? {
        lock.lock(); defer { lock.unlock() }
        return components[tag]
    }

    public func registered() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return components.keys.sorted()
    }
}
