// Bootstraps the foundational `:wun/*` Compose renderers into a
// registry. Calls match the cljc-side wun.foundation.components on
// the server, modulo each platform's renderer registration.
//
// Usage:
//   val registry = Registry()
//   WunFoundation.register(registry)
//   // or
//   WunFoundation.registerDefaults()        // populates Registry.shared

package wun.foundation

import wun.Registry

object WunFoundation {
    fun register(registry: Registry) {
        registry.register("wun/Stack",      StackRenderer.render)
        registry.register("wun/Text",       TextRenderer.render)
        registry.register("wun/Image",      ImageRenderer.render)
        registry.register("wun/Button",     ButtonRenderer.render)
        registry.register("wun/Card",       CardRenderer.render)
        registry.register("wun/Avatar",     AvatarRenderer.render)
        registry.register("wun/Input",      InputRenderer.render)
        registry.register("wun/List",       ListRenderer.render)
        registry.register("wun/Spacer",     SpacerRenderer.render)
        registry.register("wun/ScrollView", ScrollViewRenderer.render)
        registry.register("wun/WebFrame",   WebFrameRenderer.render)
    }

    fun registerDefaults() = register(Registry.shared)
}
