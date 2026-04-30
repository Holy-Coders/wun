// User-namespace components that participate in the same Wun
// registry the framework's `:wun/*` renderers register through.
//
// Host apps wire this in alongside `WunFoundation.register(into:)`:
//
//     let registry = Registry()
//     WunFoundation.register(into: registry)
//     WunExample.register(into: registry)
//
// The component keyword (here `:myapp/Greeting`) is exactly the
// same shape the brief's user-defined `:myapp/RichEditor` example
// uses, mirrored across the cljc `defcomponent` on the server.

import Foundation
import Wun

public enum WunExample {
    public static func register(into registry: Registry) {
        registry.register("myapp/Greeting", Greeting.render)
        // AUTO-REGISTER-MARK
    }

    public static let registeredComponents: [String] = [
        "myapp/Greeting",
    ]
}
