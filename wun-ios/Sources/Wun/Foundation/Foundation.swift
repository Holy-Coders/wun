// Bootstraps the foundational `:wun/*` vocabulary into a registry.
// Calls match the cljc-side wun.foundation.components on the server,
// modulo each platform's renderer registration.
//
// Usage:
//   let registry = Registry()
//   WunFoundation.register(into: registry)
//   // or simply:
//   WunFoundation.registerDefaults()       // populates Registry.shared

import Foundation

public enum WunFoundation {
    public static func register(into registry: Registry) {
        registry.register("wun/Stack", WunStack.render)
        registry.register("wun/Text",  WunText.render)
        // Phase 2.D adds Image, Button, Card, Avatar, Input, List,
        // Spacer, ScrollView. WebFrame lands in 2.F.
    }

    public static func registerDefaults() {
        register(into: .shared)
    }
}
