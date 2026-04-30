// Template Compose renderer. Same shape as wun.foundation.*Renderer:
// a `WunComponent` closure that takes (props, children) and emits
// composables. Children are pre-rendered `WunNode`s; flatten them to
// a single string with `Children.flatten(children)` or walk them
// yourself with `WunView(kid)`.

package myapp.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent
import wun.foundation.Children

object MyComponentRenderer {
    val render: WunComponent = { props, children ->
        val label = (props["label"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: Children.flatten(children).ifEmpty { "Hello from MyComponent" }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.primary.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text("✨")
            Spacer(modifier = Modifier.width(6.dp))
            Text(label)
        }
    }
}
