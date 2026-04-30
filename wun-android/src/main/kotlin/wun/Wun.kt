// Top-level Wun namespace + global hooks. Mirrors the Swift `Wun`
// enum -- intentDispatcher is the closure renderers call when the
// user fires an intent (Button onClick, Input on submit, ...);
// serverBase is the URL WebFrame relative `:src` strings resolve
// against.

package wun

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object Wun {
    const val VERSION = "0.1.0-phase3c"

    /** Called by Button/Input/etc. when an intent is fired. Set this
     *  from your host app at startup; defaults to a stderr warning
     *  so missing wiring is visible in dev. */
    @Volatile
    var intentDispatcher: (intent: String, params: Map<String, JsonElement>) -> Unit =
        { intent, _ ->
            System.err.println("[wun] no intent dispatcher set; dropped $intent")
        }

    /** Server origin used for resolving relative URLs in `:wun/WebFrame`
     *  payloads. The server emits relative `:src` paths
     *  (e.g. "/web-frames/wun/Card"); the WebFrame renderer joins
     *  them against this base. */
    @Volatile
    var serverBase: String? = null
}
