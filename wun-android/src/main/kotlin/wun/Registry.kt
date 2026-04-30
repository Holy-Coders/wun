// Open component registry. Each component keyword (a namespaced
// string like "wun/Stack" or "myapp/RichEditor") maps to a
// platform-specific renderer. The `WunComponent` typealias lives
// here as a placeholder; phase 3.C swaps in a real Compose
// signature `(Modifier, Map<String, JsonElement>, List<WunNode>) ->
// (@Composable () -> Unit)` once we have a UI host.
//
// Until then renderers register as plain `Any` opaque tokens so
// downstream code can list / look them up.

package wun

class Registry {
    private val lock = Any()
    private val components = mutableMapOf<String, Any>()

    fun register(tag: String, renderer: Any) {
        synchronized(lock) { components[tag] = renderer }
    }

    fun lookup(tag: String): Any? = synchronized(lock) { components[tag] }

    fun registered(): List<String> =
        synchronized(lock) { components.keys.sorted() }

    companion object {
        val shared: Registry = Registry()
    }
}
