// SSE client built on OkHttp-sse. Decodes each `event: patch` frame
// into an Envelope and forwards it through the supplied callback,
// surfacing connect / disconnect events alongside.
//
// Mirrors the Swift SSEClient. OkHttp's EventSource handles the
// frame-splitting subtleties (including blank-line frame
// terminators) we had to hand-roll on Apple's URLSession.bytes;
// nothing for us to fight.
//
// Reconnection: OkHttp's EventSource doesn't reconnect on its own,
// so we layer an exponential-backoff retry on top -- matching the
// Swift side. Each successful onOpen resets the attempt counter; a
// failure with no successful frames extends backoff up to 30s.

package wun

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SSEClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient,
    private val onConnected: () -> Unit = {},
    private val onDisconnect: (Throwable?) -> Unit = {},
    private val onEnvelope: (Envelope) -> Unit,
    /** Set to false in tests that want exactly one connect attempt. */
    var autoReconnect: Boolean = true,
) {
    private var source: EventSource? = null
    private val attempts = AtomicInteger(0)
    @Volatile private var pendingRetry: ScheduledFuture<*>? = null
    @Volatile private var stopped = false

    fun start() {
        if (source != null) return
        stopped = false
        connect()
    }

    private fun connect() {
        if (stopped) return
        val builder = Request.Builder().url(url)
        builder.header("Accept", "text/event-stream")
        builder.header("Cache-Control", "no-cache")
        for ((k, v) in headers) builder.header(k, v)

        var connectedThisRound = false
        source = EventSources.createFactory(client).newEventSource(
            builder.build(),
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    connectedThisRound = true
                    attempts.set(0)
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
                    scheduleReconnect(connectedThisRound)
                }

                override fun onClosed(eventSource: EventSource) {
                    onDisconnect(null)
                    scheduleReconnect(connectedThisRound)
                }
            }
        )
    }

    private fun scheduleReconnect(succeededAtLeastOnce: Boolean) {
        source = null
        if (stopped || !autoReconnect) return
        val attempt = if (succeededAtLeastOnce) {
            attempts.set(0)
            1
        } else attempts.incrementAndGet()
        val delayMs = backoffMs(attempt)
        pendingRetry = scheduler.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun backoffMs(attempt: Int): Long {
        // 1s, 2s, 4s, 8s, 16s, 30s cap.
        val exp = minOf(attempt - 1, 5).coerceAtLeast(0)
        val base = 1_000L * (1L shl exp)
        val jitter = (0..500).random().toLong()
        return minOf(30_000L, base + jitter)
    }

    fun stop() {
        stopped = true
        pendingRetry?.cancel(false)
        pendingRetry = null
        source?.cancel()
        source = null
    }

    companion object {
        // Long readTimeout so the SSE stream can sit idle without
        // OkHttp killing it.
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(java.time.Duration.ofMinutes(5))
            .build()

        // Daemon scheduler so the reconnect timer never holds the JVM
        // alive when an app shuts down.
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wun-sse-reconnect").apply { isDaemon = true }
        }
    }
}
