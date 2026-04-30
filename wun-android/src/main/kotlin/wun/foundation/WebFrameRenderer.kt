// Compose renderer for `:wun/WebFrame`. Compose Multiplatform
// Desktop doesn't bundle a WebView; phase 3.C ships a styled
// placeholder showing the missing component + WebFrame URL. A real
// Android-side WebView lands in 3.E.

package wun.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
            }
        }
    }

    private fun resolveUrl(src: String): String {
        if (src.startsWith("http://") || src.startsWith("https://")) return src
        val base = Wun.serverBase ?: return src
        return base.trimEnd('/') + (if (src.startsWith("/")) src else "/$src")
    }
}
