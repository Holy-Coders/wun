// Compose renderer for `:wun/Card`. Optional :title headline, then
// children stacked vertically, all inside a padded rounded surface.

package wun.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import wun.WunComponent
import wun.WunView

object CardRenderer {
    val render: WunComponent = { props, children ->
        val title = (props["title"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }
        Card(shape = RoundedCornerShape(12.dp), elevation = 0.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                children.forEach { kid -> WunView(kid) }
            }
        }
    }
}
