// Compose renderer for `:wun/Badge`. `:tone` picks a semantic colour
// (info / success / warning / danger). Unknown tones fall back to
// info so server-side additions don't break older clients.

package wun.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent

object BadgeRenderer {
    val render: WunComponent = { props, children ->
        val tone = (props["tone"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: "info"
        val (bg, fg) = when (tone) {
            "success" -> Color(0xFFE1F6E9) to Color(0xFF1B6C3A)
            "warning" -> Color(0xFFFFF3D6) to Color(0xFF7A5300)
            "danger"  -> Color(0xFFFDE2E2) to Color(0xFF9B1C1C)
            else      -> Color(0xFFE8F1FF) to Color(0xFF0A4EA3)
        }
        val label = Children.flatten(children)
        Text(
            text       = label,
            color      = fg,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
