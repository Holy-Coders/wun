// Compose renderer for `:wun/WebFrame`. Compose Multiplatform Desktop
// doesn't bundle a WebView; an embedded view would require KCEF
// (JCEF wrapper) or Compose Multiplatform's experimental WebView,
// neither of which is in the stock dep set.
//
// Phase 6 strategy: render an actionable card -- shows the missing
// component name and provides a button that opens the URL in the
// system browser via java.awt.Desktop. This lets Wun apps degrade
// usefully on the Compose Desktop target until embedded JCEF lands.
//
// On the real Android target (when the project gets one), this file
// is replaced with an `AndroidView { WebView }` impl. The `Wun.openUrl`
// hook below lets host apps override navigation -- e.g. with a
// custom WebView host -- without forking the framework.

package wun.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import wun.Wun
import wun.WunComponent
import java.awt.Desktop
import java.net.URI

object WebFrameRenderer {
    val render: WunComponent = { props, _ ->
        val missing = (props["missing"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        val src = (props["src"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        val resolved = src?.let { resolveUrl(it) }
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                .background(Color(0xFFF6F6F6))
                .padding(12.dp),
        ) {
            Text("WebFrame fallback", fontWeight = FontWeight.SemiBold)
            if (missing != null) {
                Text(
                    "missing renderer for $missing",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (resolved != null) {
                Text(
                    "src=$resolved",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { Wun.openUrl(resolved) }) {
                        Text("Open in browser")
                    }
                }
            }
        }
    }

    private fun resolveUrl(src: String): String {
        if (src.startsWith("http://") || src.startsWith("https://")) return src
        val base = Wun.serverBase ?: return src
        return base.trimEnd('/') + (if (src.startsWith("/")) src else "/$src")
    }
}

/** Default URL opener for Compose Desktop: launches the system browser
 *  via java.awt.Desktop. Host apps replace `Wun.openUrl` with their own
 *  hook -- e.g. an in-app WebView host on real Android, or a custom
 *  Hotwire-Native-style intercept. */
internal fun openUrlInSystemBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Throwable) {
        // Headless / sandboxed environments simply don't open the URL.
        // The visible src= line above tells the user where to navigate.
    }
}
