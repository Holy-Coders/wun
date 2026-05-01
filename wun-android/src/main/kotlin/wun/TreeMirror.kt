// Synchronised mirror of the server's UI tree. Producers fed by the
// SSE callback (background OkHttp dispatcher thread) and consumers
// reading from any thread. Synchronized on a private lock to avoid
// the @MainActor / actor / coroutine complexity until a Compose host
// app needs it; mutations are tiny so contention isn't a concern.

package wun

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

class TreeMirror(initial: JsonElement = JsonNull) {
    private val lock = Any()
    @Volatile private var _tree: JsonElement = initial
    @Volatile private var _state: JsonElement = JsonNull
    @Volatile private var _lastResolvedIntent: String? = null
    @Volatile private var _connId: String? = null
    @Volatile private var _screenStack: List<String> = emptyList()
    @Volatile private var _presentations: List<String> = emptyList()
    @Volatile private var _meta: JsonElement? = null
    @Volatile private var _title: String? = null

    val tree:  JsonElement get() = _tree
    val state: JsonElement get() = _state
    val lastResolvedIntent: String? get() = _lastResolvedIntent
    val connId: String? get() = _connId
    val screenStack: List<String> get() = _screenStack
    val presentations: List<String> get() = _presentations
    val topPresentation: String get() = _presentations.lastOrNull() ?: "push"
    val meta: JsonElement? get() = _meta
    val title: String? get() = _title

    fun apply(envelope: Envelope) {
        synchronized(lock) {
            if (envelope.patches.isNotEmpty()) {
                _tree = Diff.apply(_tree, envelope.patches)
            }
            envelope.state?.let { _state = it }
            envelope.connId?.let { _connId = it }
            envelope.screenStack?.let { _screenStack = it }
            envelope.presentations?.let { _presentations = it }
            envelope.meta?.let { m ->
                _meta = m
                if (m is kotlinx.serialization.json.JsonObject) {
                    val t = m["title"]
                    if (t is kotlinx.serialization.json.JsonPrimitive && t.isString) {
                        _title = t.content
                    }
                }
            }
            _lastResolvedIntent = envelope.resolvesIntent
        }
    }

    fun reset(to: JsonElement) {
        synchronized(lock) { _tree = to }
    }

    /**
     * Hydrate from a previously-saved snapshot (cold start). Bypasses
     * `apply` so we don't fire a fake :resolves-intent. Server's next
     * envelope reconciles any drift.
     */
    fun hydrate(tree: JsonElement,
                state: JsonElement,
                screenStack: List<String>,
                meta: JsonElement?,
                title: String?) {
        synchronized(lock) {
            _tree = tree
            _state = state
            _screenStack = screenStack
            _meta = meta
            _title = title
        }
    }
}
