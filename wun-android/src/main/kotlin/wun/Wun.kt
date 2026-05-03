// Top-level Wun namespace + global hooks. Mirrors the Swift `Wun`
// enum -- intentDispatcher is the closure renderers call when the
// user fires an intent (Button onClick, Input on submit, ...);
// serverBase is the URL WebFrame relative `:src` strings resolve
// against.

package wun

import kotlinx.serialization.json.JsonElement
import wun.foundation.openUrlInSystemBrowser

object Wun {
    const val VERSION = "0.1.0-phase6"

    /** Wire envelope versions this build can decode. The SSE handshake
     *  negotiates against this set via `?envelope=` query param. */
    val SUPPORTED_ENVELOPE_VERSIONS: Set<Int> = setOf(1, 2)

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

    /** Open `url` outside of the Wun-managed UI. Default: launches the
     *  system browser via `java.awt.Desktop` (Compose Desktop). Host
     *  apps replace this hook to embed a real WebView, integrate
     *  Hotwire Native, or route through their own navigation system. */
    @Volatile
    var openUrl: (String) -> Unit = ::openUrlInSystemBrowser
}
