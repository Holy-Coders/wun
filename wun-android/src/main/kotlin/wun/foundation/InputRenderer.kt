// Compose renderer for `:wun/Input`. Local `remember`-backed state
// for edits; on commit (Enter), fires `:on-change` through
// `Wun.intentDispatcher` with the new value injected as a `value`
// param.

package wun.foundation

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wun.Wun
import wun.WunComponent

object InputRenderer {
    val render: WunComponent = { props, _ ->
        val initial = (props["value"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } ?: ""
        val placeholder = (props["placeholder"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } ?: ""
        val onChange = props["on-change"] as? JsonObject
        var local by remember(initial) { mutableStateOf(initial) }
        OutlinedTextField(
            value = local,
            onValueChange = { local = it },
            label = { Text(placeholder) },
            keyboardActions = KeyboardActions(onDone = {
                val intent = (onChange?.get("intent") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: return@KeyboardActions
                val params: MutableMap<String, JsonElement> =
                    ((onChange["params"] as? JsonObject)?.toMutableMap()
                        ?: mutableMapOf())
                params["value"] = JsonPrimitive(local)
                Wun.intentDispatcher(intent, params)
            }),
        )
    }
}
