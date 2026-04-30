// Component-pack template. Calls `register` from your host app
// alongside `WunFoundation.register(...)`.

package myapp.example

import wun.Registry

object MyAppExample {
    fun register(registry: Registry) {
        registry.register("myapp/MyComponent", MyComponentRenderer.render)
    }
}
