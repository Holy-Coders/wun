// Hot-cache persistence for the JVM/Android client. Mirror of
// wun.web.persist + wun-ios Persistence: on cold start we hydrate
// the last-known tree + state + screen-stack + meta from
// java.util.prefs.Preferences so the user sees the prior UI
// immediately on relaunch rather than a blank canvas.
//
// java.util.prefs has a small per-entry size limit (~8 KiB by
// default). Trees in the demo fit comfortably; for larger
// apps a future iteration could swap to a Room/SQLite store on
// real Android. Composite-build Compose Desktop has no such
// problem -- preferences live in ~/Library/Preferences (macOS)
// or equivalent.

package wun

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.prefs.Preferences

@Serializable
data class Snapshot(
    val tree: JsonElement = JsonNull,
    val state: JsonElement = JsonNull,
    val screenStack: List<String> = emptyList(),
    val meta: JsonElement? = null,
    val title: String? = null,
    val savedAt: Long = 0L,
)

object Persistence {
    /** Snapshot age in ms beyond which we discard rather than hydrate. */
    const val STALE_MS: Long = 24L * 60 * 60 * 1000

    private val prefs: Preferences = Preferences.userRoot().node("wun/snapshots")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(snap: Snapshot, path: String = "/") {
        try {
            prefs.put(key(path), json.encodeToString(Snapshot.serializer(), snap))
            prefs.flush()
        } catch (e: Throwable) {
            println("[wun] persist save failed: ${e.message}")
        }
    }

    fun load(path: String = "/"): Snapshot? {
        val raw = prefs.get(key(path), null) ?: return null
        return try {
            val snap = json.decodeFromString(Snapshot.serializer(), raw)
            val age = System.currentTimeMillis() - snap.savedAt
            if (age < STALE_MS) snap else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(path: String = "/") {
        prefs.remove(key(path))
        prefs.flush()
    }

    /**
     * Pull a server-issued session token out of the persisted state for
     * `path`, if any. Mirrors `wun.web.core/persisted-session-token` and
     * the Swift `Persistence.sessionToken`. The host wires this into
     * `SSEClient`'s `headers` map as `X-Wun-Session: <t>` so the server's
     * init-state-fn rehydrates the user's slice during the SSE handshake.
     *
     * Returns null when no snapshot exists, the snapshot is stale, or
     * the snapshot has no `:session.token`. Stale tokens are a no-op
     * server-side (the sessions table lookup returns null and the
     * init-state-fn skips the merge), so don't bother validating
     * client-side.
     */
    fun sessionToken(path: String = "/"): String? {
        val snap = load(path) ?: return null
        val state = snap.state as? JsonObject ?: return null
        val session = state["session"] as? JsonObject ?: return null
        val token = (session["token"] as? JsonPrimitive)?.contentOrNull
        return token?.takeIf { it.isNotEmpty() }
    }

    private fun key(path: String): String = "snapshot:$path"
}
