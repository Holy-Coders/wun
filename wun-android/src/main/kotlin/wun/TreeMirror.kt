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

    val tree:  JsonElement get() = _tree
    val state: JsonElement get() = _state
    val lastResolvedIntent: String? get() = _lastResolvedIntent

    fun apply(envelope: Envelope) {
        synchronized(lock) {
            if (envelope.patches.isNotEmpty()) {
                _tree = Diff.apply(_tree, envelope.patches)
            }
            envelope.state?.let { _state = it }
            _lastResolvedIntent = envelope.resolvesIntent
        }
    }

    fun reset(to: JsonElement) {
        synchronized(lock) { _tree = to }
    }
}
