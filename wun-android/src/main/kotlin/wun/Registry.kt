// Open component registry. Each component keyword (a namespaced
// string like "wun/Stack" or "myapp/RichEditor") maps to a
// `WunComponent` -- a function of (props, children) that returns a
// composable. Framework code and user code register through the
// same `register` API; the registry doesn't distinguish the two,
// mirroring the cljc `wun.components/registry`.

package wun

class Registry {
    private val lock = Any()
    private val components = mutableMapOf<String, WunComponent>()

    fun register(tag: String, renderer: WunComponent) {
        synchronized(lock) { components[tag] = renderer }
    }

    fun lookup(tag: String): WunComponent? =
        synchronized(lock) { components[tag] }

    fun registered(): List<String> =
        synchronized(lock) { components.keys.sorted() }

    companion object {
        val shared: Registry = Registry()
    }
}
