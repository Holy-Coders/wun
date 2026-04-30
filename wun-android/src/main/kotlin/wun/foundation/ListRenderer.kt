// Compose renderer for `:wun/List`. Vertical lazy list of children.

package wun.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import wun.WunComponent
import wun.WunView

object ListRenderer {
    val render: WunComponent = { _, children ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(children) { kid -> WunView(kid) }
        }
    }
}
