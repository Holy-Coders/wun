// Compose renderer for `:wun/Switch`. `:value` controls the toggle;
// `:on-toggle` fires the intent with the new boolean value merged
// into params under `value`. Optimistic local state snaps back to
// the server's value when it changes underneath us.

package wun.foundation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wun.Wun
import wun.WunComponent

object SwitchRenderer {
    val render: WunComponent = { props, children ->
        val serverValue = props["value"]?.jsonPrimitive?.booleanOrNull ?: false
        val onToggle    = props["on-toggle"] as? JsonObject
        val label       = Children.flatten(children)

        var local by remember(serverValue) { mutableStateOf(serverValue) }
        LaunchedEffect(serverValue) {
            if (local != serverValue) local = serverValue
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = local,
                onCheckedChange = { newValue ->
                    local = newValue
                    if (onToggle != null) {
                        val intent = (onToggle["intent"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                            ?: return@Switch
                        @Suppress("UNCHECKED_CAST")
                        val baseParams = (onToggle["params"] as? JsonObject)
                            ?: emptyMap<String, JsonElement>()
                        val params = buildJsonObject {
                            for ((k, v) in baseParams) put(k, v)
                            put("value", JsonPrimitive(newValue))
                        }
                        Wun.intentDispatcher(intent, params)
                    }
                },
            )
            if (label.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, color = MaterialTheme.colors.onSurface)
            }
        }
    }
}
