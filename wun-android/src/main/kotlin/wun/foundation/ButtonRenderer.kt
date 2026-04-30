// Compose renderer for `:wun/Button`. `:on-press` is an intent ref
// `{:intent ... :params {...}}` -- when the user clicks, we fire
// the intent through `Wun.intentDispatcher`, which the host wires
// up to a real POST.

package wun.foundation

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wun.Wun
import wun.WunComponent

object ButtonRenderer {
    val render: WunComponent = { props, children ->
        val title = Children.flatten(children)
        val onPress = props["on-press"] as? JsonObject
        Button(onClick = {
            val intent = (onPress?.get("intent") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: return@Button
            @Suppress("UNCHECKED_CAST")
            val params = (onPress["params"] as? JsonObject)
                ?: emptyMap<String, JsonElement>()
            Wun.intentDispatcher(intent, params)
        }) {
            Text(title)
        }
    }
}
