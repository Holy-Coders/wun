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
        registry.register("wun/Stack",      WunStack.render)
        registry.register("wun/Text",       WunText.render)
        registry.register("wun/Image",      WunImage.render)
        registry.register("wun/Button",     WunButton.render)
        registry.register("wun/Card",       WunCard.render)
        registry.register("wun/Avatar",     WunAvatar.render)
        registry.register("wun/Input",      WunInput.render)
        registry.register("wun/List",       WunList.render)
        registry.register("wun/Spacer",     WunSpacer.render)
        registry.register("wun/ScrollView", WunScrollView.render)
        registry.register("wun/WebFrame",   WunWebFrame.render)
    }

    public static func registerDefaults() {
        register(into: .shared)
    }
}
