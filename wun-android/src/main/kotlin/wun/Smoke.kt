// End-to-end smoke for the Android Kotlin client. Connects to the
// running phase-2 server, applies received patches into a
// TreeMirror, fires a few intents, prints a one-line summary per
// envelope.
//
// Run: gradle run                       (defaults to localhost:8080)
//      gradle run --args='<sse-url>'    (override SSE URL)
//
// Mirrors wun-ios/Sources/WunSmoke; same wire, same trail.

package wun

import kotlinx.serialization.json.*

private const val DEFAULT_BASE = "http://localhost:8080"

private val capabilities = listOf(
    "wun/Stack@1", "wun/Text@1", "wun/Image@1", "wun/Button@1",
    "wun/Card@1",  "wun/Avatar@1", "wun/Input@1", "wun/List@1",
    "wun/Spacer@1", "wun/ScrollView@1", "wun/WebFrame@1",
).joinToString(",")

fun main(args: Array<String>) {
    val baseUrl = if (args.isNotEmpty()) args[0] else DEFAULT_BASE
    val sseUrl  = "$baseUrl/wun"

    val mirror     = TreeMirror()
    val dispatcher = IntentDispatcher(
        baseUrl,
        onError = { intent, status, error ->
            println("[err] intent=$intent status=$status error=$error")
        }
    )

    val client = SSEClient(
        url = sseUrl,
        headers = mapOf(
            "X-Wun-Capabilities" to capabilities,
            "X-Wun-Format"       to "json",
        ),
        onConnected  = { println("[smoke] connected to $sseUrl (caps via header)") },
        onDisconnect = { e ->
            println("[smoke] disconnected: ${e?.message ?: "(graceful)"}")
            kotlin.system.exitProcess(0)
        },
        onEnvelope = { envelope ->
            mirror.apply(envelope)
            val ops = envelope.patches
                .joinToString(" ") { "${it.op}@${it.path}" }
            val resolves = envelope.resolvesIntent?.take(8) ?: "-"
            val counter = (mirror.state as? JsonObject)
                ?.get("counter")?.jsonPrimitive?.intOrNull
                ?.let { "{counter: $it}" } ?: "${mirror.state}"
            println("[${envelope.status}] resolves=$resolves ops=[$ops] state=$counter")
        }
    )
    client.start()

    // Fire a few intents so we see the round trip.
    val later = java.util.Timer(true)
    fun delay(ms: Long, body: () -> Unit) {
        later.schedule(object : java.util.TimerTask() { override fun run() { body() } }, ms)
    }
    delay(1000)  { dispatcher.dispatch("counter/inc"); println("[smoke] dispatched counter/inc") }
    delay(1500)  { dispatcher.dispatch("counter/by", mapOf("n" to JsonPrimitive(5)));
                   println("[smoke] dispatched counter/by 5") }
    delay(2000)  { dispatcher.dispatch("counter/by", mapOf("n" to JsonPrimitive("oops")));
                   println("[smoke] dispatched counter/by 'oops' (should 400)") }
    delay(2500)  { dispatcher.dispatch("counter/reset"); println("[smoke] dispatched counter/reset") }

    // Keep the process alive; SSEClient + dispatcher run on OkHttp's
    // dispatcher thread pool.
    Thread.currentThread().join()
}
