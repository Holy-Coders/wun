// Compose renderer for `:wun/Stack`. Direction defaults to column;
// `:row` switches to Row. `:gap` and `:padding` are pixel ints.

package wun.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent
import wun.WunView

object StackRenderer {
    val render: WunComponent = renderer@ { props, children ->
        val direction = (props["direction"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        val gap     = (props["gap"]?.jsonPrimitive?.intOrNull) ?: 0
        val padding = (props["padding"]?.jsonPrimitive?.intOrNull) ?: 0
        val mod = Modifier.padding(padding.dp)
        if (direction == "row") {
            Row(modifier = mod,
                horizontalArrangement = Arrangement.spacedBy(gap.dp),
                verticalAlignment = Alignment.CenterVertically) {
                children.forEach { kid -> WunView(kid) }
            }
        } else {
            Column(modifier = mod,
                   verticalArrangement = Arrangement.spacedBy(gap.dp)) {
                children.forEach { kid -> WunView(kid) }
            }
        }
    }
}
