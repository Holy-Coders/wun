// Compose renderer for `:wun/ScrollView`. Vertical scroll by
// default; `:direction = "horizontal"` switches to horizontal.

package wun.foundation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent
import wun.WunView

object ScrollViewRenderer {
    val render: WunComponent = { props, children ->
        val horizontal = (props["direction"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } == "horizontal"
        val scrollState = rememberScrollState()
        if (horizontal) {
            Row(modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                children.forEach { kid -> WunView(kid) }
            }
        } else {
            Column(modifier = Modifier.verticalScroll(scrollState),
                   verticalArrangement = Arrangement.spacedBy(8.dp)) {
                children.forEach { kid -> WunView(kid) }
            }
        }
    }
}
