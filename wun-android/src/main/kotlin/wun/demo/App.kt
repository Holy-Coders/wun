// Compose Desktop demo. Open the wun-android project in IntelliJ /
// Android Studio (or just `gradle run` from CLI) and a window
// appears showing the live counter screen the wun-server emits,
// rendered through the Compose renderers in `wun.foundation`.
//
// Parallel of wun-ios-example/Sources/WunDemoMac. The brief's full
// foundational set is registered up front; clicking buttons fires
// real intents over /intent.

package wun.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.serialization.json.*
import wun.Envelope
import wun.IntentDispatcher
import wun.LocalWunRegistry
import wun.Registry
import wun.SSEClient
import wun.TreeMirror
import wun.Wun
import wun.WunNode
import wun.WunView
import wun.foundation.WunFoundation

private const val BASE_URL = "http://localhost:8080"

private val capabilityHeader: String by lazy {
    Registry.shared.registered().joinToString(",") { "$it@1" }
}

fun main() = application {
    // Populate the shared registry once, in process scope.
    val registry = remember {
        Registry().also { WunFoundation.register(it) }
    }

    val mirror = remember { TreeMirror() }
    var status by remember { mutableStateOf("connecting…") }
    var tree   by remember { mutableStateOf<WunNode>(WunNode.Null) }
    var state  by remember { mutableStateOf<JsonElement>(JsonNull) }

    val dispatcher = remember {
        IntentDispatcher(BASE_URL, onError = { intent, code, _ ->
            println("[demo] intent $intent -> $code")
        })
    }
    val sse = remember {
        SSEClient(
            url = "$BASE_URL/wun",
            headers = mapOf(
                "X-Wun-Capabilities" to registry.registered()
                    .joinToString(",") { "$it@1" },
                "X-Wun-Format"       to "json",
            ),
            onConnected  = { status = "connected" },
            onDisconnect = { e ->
                status = if (e == null) "disconnected" else "disconnected: ${e.message}"
            },
            onEnvelope = { env ->
                mirror.apply(env)
                tree  = WunNode.fromJson(mirror.tree)
                state = mirror.state
            },
        )
    }

    LaunchedEffect(Unit) {
        Wun.serverBase = BASE_URL
        Wun.intentDispatcher = { intent, params ->
            dispatcher.dispatch(intent, params)
        }
        sse.start()
    }

    val windowState = rememberWindowState(width = 540.dp, height = 640.dp)
    Window(onCloseRequest = ::exitApplication,
           title = "Wun · phase 3 demo",
           state = windowState) {
        CompositionLocalProvider(LocalWunRegistry provides registry) {
            Surface(status = status, tree = tree, state = state)
        }
    }
}

@Composable
private fun Surface(status: String, tree: WunNode, state: JsonElement) {
    Column(modifier = Modifier.fillMaxSize()) {
        StatusBar(status)
        Divider()

        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
        ) {
            WunView(tree)
        }

        Divider()
        StateBar(state)
    }
}

@Composable
private fun StatusBar(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (status == "connected") Color(0xFF34C759) else Color(0xFFFF9500)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(status, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.weight(1f))
        Text("Wun ${Wun.VERSION}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun StateBar(state: JsonElement) {
    val summary = if (state is JsonObject) {
        val counter = state["counter"]?.jsonPrimitive?.intOrNull
        if (counter != null) "{counter: $counter}" else state.toString()
    } else state.toString()
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("state: $summary",
             fontSize = 10.sp,
             fontFamily = FontFamily.Monospace,
             fontWeight = FontWeight.Normal,
             color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
    }
}

