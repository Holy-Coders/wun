// Compose renderer for `:myapp/Greeting`. Same shape as the
// foundational `:wun/*` renderers in wun-android/foundation, just
// in a user-namespace package and shipped from a separate Gradle
// build.

package myapp.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent

object GreetingRenderer {
    val render: WunComponent = { props, _ ->
        val name = (props["name"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } ?: "world"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null,
                 tint = Color(0xFFFF9500))
            Text("Hello, $name!", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
