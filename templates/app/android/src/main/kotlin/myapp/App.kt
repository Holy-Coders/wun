// Compose Desktop demo for myapp. Mirrors wun-android/src/main/kotlin/wun/demo/App.kt
// stripped to a minimum: register the foundational :wun/* renderers,
// open SSE, hand the live tree to WunView. Add custom :myapp/*
// renderers via `registry.register(...)` alongside the WunFoundation block.

package myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

fun main() = application {
    val registry = remember {
        Registry().also {
            WunFoundation.register(it)
            // -- register your :myapp/* renderers here --
            // it.register("myapp/Card", MyAppCard.render)
        }
    }

    val mirror = remember { TreeMirror() }
    var status by remember { mutableStateOf("connecting…") }
    var tree   by remember { mutableStateOf<WunNode>(WunNode.Null) }

    val dispatcher = remember {
        IntentDispatcher(
            baseUrl = BASE_URL,
            onError = { intent, code, _ -> println("[myapp] intent $intent -> $code") },
            connIdProvider = { mirror.connId },
        )
    }
    val sse = remember {
        SSEClient(
            url = "$BASE_URL/wun",
            headers = mapOf(
                "X-Wun-Capabilities" to registry.registered().joinToString(",") { "$it@1" },
                "X-Wun-Format"       to "json",
            ),
            onConnected  = { status = "connected" },
            onDisconnect = { e -> status = e?.let { "disconnected: ${it.message}" } ?: "disconnected" },
            onEnvelope   = { env ->
                mirror.apply(env)
                tree = WunNode.fromJson(mirror.tree)
            },
        )
    }

    LaunchedEffect(Unit) {
        Wun.serverBase = BASE_URL
        Wun.intentDispatcher = { intent, params -> dispatcher.dispatch(intent, params) }
        sse.start()
    }

    Window(onCloseRequest = ::exitApplication,
           title = "myapp",
           state = rememberWindowState(width = 480.dp, height = 540.dp)) {
        CompositionLocalProvider(LocalWunRegistry provides registry) {
            Column(modifier = Modifier.fillMaxSize()) {
                StatusBar(status)
                Divider()
                Box(modifier = Modifier
                    .weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)) {
                    WunView(tree)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(status: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier
            .size(8.dp).clip(CircleShape)
            .background(if (status == "connected") Color(0xFF34C759) else Color(0xFFFF9500)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(status, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
    }
}
