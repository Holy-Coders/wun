// POSTs JSON intent envelopes to /intent. Mirrors the Swift
// `IntentDispatcher`: generates a UUID per call, forwards intent +
// params + id, surfaces 400 errors via `onError`. Uses the same
// OkHttp client as the SSE side by default.

package wun

import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

class IntentDispatcher(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val onError: (intent: String, status: Int, error: JsonElement?) -> Unit
        = { _, _, _ -> },
    /**
     * Returns the current connection id, or null before the first SSE
     * envelope has arrived. The dispatcher includes it on every POST
     * so framework intents (`wun/navigate`, `wun/pop`) are routed to
     * this connection's screen-stack on the server.
     */
    private val connIdProvider: () -> String? = { null },
) {
    private val mediaType = "application/json".toMediaType()

    /** Fire `intent` with `params`. Returns the generated intent id. */
    fun dispatch(intent: String, params: Map<String, JsonElement> = emptyMap()): String {
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("intent", JsonPrimitive(intent))
            put("params", JsonObject(params))
            put("id",     JsonPrimitive(id))
            connIdProvider()?.let { put("conn-id", JsonPrimitive(it)) }
        }.toString().toRequestBody(mediaType)

        val req = Request.Builder()
            .url("$baseUrl/intent")
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(intent, -1, JsonPrimitive(e.message ?: "io"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) return
                    val raw  = r.body?.string()
                    val errEnvelope = raw?.let { runCatching { Envelope.decode(it) }.getOrNull() }
                    onError(intent, r.code, errEnvelope?.error)
                }
            }
        })
        return id
    }

    /** Push `path` onto the connection's screen-stack server-side. */
    fun navigateToPath(path: String): String =
        dispatch("wun/navigate", mapOf("path" to JsonPrimitive(path)))

    /** Push `screenKey` (e.g. `app/about`) onto the screen-stack. */
    fun navigateToScreen(screenKey: String): String =
        dispatch("wun/navigate", mapOf("screen" to JsonPrimitive(screenKey)))

    /** Pop the top of the connection's screen-stack. */
    fun popScreen(): String = dispatch("wun/pop")
}
