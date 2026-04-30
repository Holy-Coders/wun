// Compose renderer for `:wun/Heading`. `:level` 1-4 maps to a font
// scale; defaults to 2 (matching the brief's :wun/Text :variant :h1
// / :h2, but as the explicit primitive in the vocabulary).

package wun.foundation

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent

object HeadingRenderer {
    val render: WunComponent = { props, children ->
        val level = (props["level"]?.jsonPrimitive?.intOrNull) ?: 2
        val (size, weight) = when (level) {
            1    -> 30.sp to FontWeight.Bold
            2    -> 22.sp to FontWeight.SemiBold
            3    -> 18.sp to FontWeight.SemiBold
            else -> 16.sp to FontWeight.SemiBold
        }
        Text(text = Children.flatten(children),
             fontSize = size,
             fontWeight = weight)
    }
}
