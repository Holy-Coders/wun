// SSE client built on OkHttp-sse. Decodes each `event: patch` frame
// into an Envelope and forwards it through the supplied callback,
// surfacing connect / disconnect events alongside.
//
// Mirrors the Swift SSEClient. OkHttp's EventSource handles the
// frame-splitting subtleties (including blank-line frame
// terminators) we had to hand-roll on Apple's URLSession.bytes;
// nothing for us to fight.

package wun

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class SSEClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient,
    private val onConnected: () -> Unit = {},
    private val onDisconnect: (Throwable?) -> Unit = {},
    private val onEnvelope: (Envelope) -> Unit,
) {
    private var source: EventSource? = null

    fun start() {
        if (source != null) return
        val builder = Request.Builder().url(url)
        builder.header("Accept", "text/event-stream")
        builder.header("Cache-Control", "no-cache")
        for ((k, v) in headers) builder.header(k, v)

        source = EventSources.createFactory(client).newEventSource(
            builder.build(),
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    onConnected()
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (type != "patch") return
                    val envelope = try {
                        Envelope.decode(data)
                    } catch (_: Throwable) {
                        return
                    }
                    onEnvelope(envelope)
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    onDisconnect(t)
                }

                override fun onClosed(eventSource: EventSource) {
                    onDisconnect(null)
                }
            }
        )
    }

    fun stop() {
        source?.cancel()
        source = null
    }

    companion object {
        // Long readTimeout so the SSE stream can sit idle without
        // OkHttp killing it.
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(java.time.Duration.ofMinutes(5))
            .build()
    }
}
