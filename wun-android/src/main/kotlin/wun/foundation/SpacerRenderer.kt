// Compose renderer for `:wun/Spacer`. With `:size` set, expands to
// that many dp in both dimensions; without, it's a 1.dp filler.

package wun.foundation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent

object SpacerRenderer {
    val render: WunComponent = { props, _ ->
        val size = props["size"]?.jsonPrimitive?.intOrNull
        if (size != null) {
            Spacer(modifier = Modifier.width(size.dp).height(size.dp))
        } else {
            Spacer(modifier = Modifier)
        }
    }
}
