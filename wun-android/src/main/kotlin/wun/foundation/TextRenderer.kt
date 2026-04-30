// Compose renderer for `:wun/Text`. `:variant` chooses h1 / h2 /
// body typography presets.

package wun.foundation

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent

object TextRenderer {
    val render: WunComponent = { props, children ->
        val variant = (props["variant"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        val text = Children.flatten(children)
        val style: TextStyle = when (variant) {
            "h1" -> TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            "h2" -> TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            else -> TextStyle(fontSize = 15.sp)
        }
        Text(text, style = style)
    }
}
