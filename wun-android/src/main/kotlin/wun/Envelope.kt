// One SSE patch envelope from the server. Same shape as the
// transit-json envelope on the web and the Swift Envelope on iOS:
//
//   {
//     "patches": [{"op":"replace","path":[],"value":<tree>}, ...],
//     "status":  "ok",
//     "state":   {"counter": 0},
//     "resolves-intent": "uuid-or-null",
//     "error":   null
//   }
//
// `patches` is optional so error envelopes (which omit it) decode.

package wun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class Envelope(
    val patches: List<Patch> = emptyList(),
    val status: String,
    val state: JsonElement? = null,
    @SerialName("resolves-intent")  val resolvesIntent: String? = null,
    @SerialName("conn-id")          val connId: String? = null,
    @SerialName("screen-stack")     val screenStack: List<String>? = null,
    val presentations: List<String>? = null,
    val meta: JsonElement? = null,
    val error: JsonElement? = null,
    /** Wire envelope version negotiated at handshake (1 or 2). */
    @SerialName("envelope-version") val envelopeVersion: Int? = null,
    /** CSRF token bound to this connection's session, sent on the
     *  initial connect frame; the client echoes it on /intent POSTs. */
    @SerialName("csrf-token")       val csrfToken: String? = null,
    /** True when this envelope is a backpressure-driven full re-render. */
    @SerialName("resync?")          val resync: Boolean? = null,
    /** Effective theme map: namespaced keyword string -> resolved value. */
    val theme: JsonElement? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): Envelope = json.decodeFromString(serializer(), text)
        fun decode(bytes: ByteArray): Envelope = decode(bytes.decodeToString())
    }
}
