// Mirror of WunExample in wun-ios-example: a separate Gradle build
// that registers `:myapp/Greeting` into a Wun Registry alongside
// whatever foundational `:wun/*` renderers the host app has wired
// up. Demonstrates the brief's "no privileged path" claim on the
// Compose / Android stack.

package myapp.example

import wun.Registry

object WunExample {
    fun register(registry: Registry) {
        registry.register("myapp/Greeting", GreetingRenderer.render)
    }

    fun registerDefaults() = register(Registry.shared)

    val components: List<String> = listOf("myapp/Greeting")
}
