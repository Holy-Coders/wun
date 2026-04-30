// Native bridge installed into an Android WebView so WebFrame
// fallbacks can fire intents through the host's IntentDispatcher
// (and therefore back over the same SSE connection) instead of
// hitting /intent over a fresh HTTP request.
//
// Compose Multiplatform Desktop (today's demo target) doesn't bundle
// a WebView, so this bridge is a no-op there. Phase 3.E ships a real
// Android WebView; install the bridge with:
//
//     val bridge = WunBridge(serializer = Json.Default)
//     webView.addJavascriptInterface(bridge, "WunBridge")
//
// The server's WebFrame stub (see wun.server.html/bridge-script)
// detects `window.WunBridge?.dispatch` and prefers it over a fetch.

package wun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * JS-callable bridge. When the Android target lands (phase 3.D), the
 * `dispatch` method should be annotated with `@JavascriptInterface`
 * directly. Today the module is `kotlin("jvm")` so we don't depend
 * on `android.webkit` -- the bridge compiles and exercises the wiring
 * via tests / the iOS side; Android wires it up at integration time.
 */
class WunBridge(
    private val json: Json = Json.Default,
) {
    fun dispatch(intent: String, paramsJson: String?) {
        val params: Map<String, JsonElement> = try {
            val obj = paramsJson?.let { json.parseToJsonElement(it) } as? JsonObject
            obj?.jsonObject ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        Wun.intentDispatcher(intent, params)
    }
}
