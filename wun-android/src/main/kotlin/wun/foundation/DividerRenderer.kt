// Compose renderer for `:wun/Divider`. `:thickness` is a pixel int;
// defaults to 1.

package wun.foundation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent

object DividerRenderer {
    val render: WunComponent = { props, _ ->
        val thickness = (props["thickness"]?.jsonPrimitive?.intOrNull) ?: 1
        Divider(
            color     = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            thickness = thickness.dp,
            modifier  = Modifier.fillMaxWidth(),
        )
    }
}
