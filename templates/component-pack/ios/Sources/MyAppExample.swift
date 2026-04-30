// Bootstraps `myapp/*` SwiftUI renderers into a Wun registry. Mirrors
// `WunFoundation.register(into:)`; the host app calls both in turn.

import Foundation
import Wun

public enum MyAppExample {
    public static func register(into registry: Registry) {
        registry.register("myapp/MyComponent", MyAppMyComponent.render)
    }
}
