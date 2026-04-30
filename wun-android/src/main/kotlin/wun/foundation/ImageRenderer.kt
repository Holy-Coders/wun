// Compose renderer for `:wun/Image`. Phase 3.C ships a placeholder
// because Compose Multiplatform Desktop doesn't have a built-in
// async image loader; adding Coil / Skiko-loadResource is later.

package wun.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import wun.WunComponent

object ImageRenderer {
    val render: WunComponent = { props, _ ->
        val src = (props["src"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        val size = (props["size"]?.jsonPrimitive?.intOrNull) ?: 80
        Box(
            modifier = Modifier.size(size.dp).background(Color(0xFFEFEFEF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(src?.let { "img: $it" } ?: "img", fontSize = 10.sp)
        }
    }
}
