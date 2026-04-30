// Compose renderer for `:wun/Link`. When `:on-press` is set the click
// fires that intent. Otherwise we rely on the host activity (Android)
// or default browser (Compose Desktop) to open `:href` -- but on
// Compose Desktop we don't have an automatic URI handler available
// from the registry, so the renderer leans on `Wun.serverBase` to
// know the page origin and prints a hint instead. Apps that want
// real URL handling should set `:on-press` to a custom intent.

package wun.foundation

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wun.Wun
import wun.WunComponent

object LinkRenderer {
    val render: WunComponent = { props, children ->
        val title = Children.flatten(children).ifEmpty {
            (props["href"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        }
        val onPress = props["on-press"] as? JsonObject
        val href    = (props["href"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val annotated: AnnotatedString = buildAnnotatedString {
            withStyle(SpanStyle(
                color          = MaterialTheme.colors.primary,
                textDecoration = TextDecoration.Underline,
                fontWeight     = FontWeight.Medium,
            )) {
                append(title)
            }
        }

        ClickableText(text = annotated) {
            if (onPress != null) {
                val intent = (onPress["intent"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: return@ClickableText
                @Suppress("UNCHECKED_CAST")
                val params = (onPress["params"] as? JsonObject)
                    ?: emptyMap<String, JsonElement>()
                Wun.intentDispatcher(intent, params)
            } else if (href != null) {
                println("[wun] :wun/Link clicked, href=$href (no on-press; host should " +
                        "register a URL handler if it wants this opened)")
            }
        }
    }
}
