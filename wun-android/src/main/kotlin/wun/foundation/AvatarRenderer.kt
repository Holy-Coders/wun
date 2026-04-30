// Compose renderer for `:wun/Avatar`. Phase 3.C ships a circular
// initials placeholder; image loading arrives when we add an
// image dep (Coil etc.).

package wun.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent

object AvatarRenderer {
    val render: WunComponent = { props, _ ->
        val initials = (props["initials"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } ?: "?"
        val size = (props["size"]?.jsonPrimitive?.intOrNull) ?: 40
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(Color.LightGray),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials,
                fontSize = (size * 0.4).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
