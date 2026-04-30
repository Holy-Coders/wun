// A node in a Wun UI tree. The wire format is Hiccup-shaped:
//   ["wun/Stack", {"gap": 12}, ["wun/Text", {"variant":"h1"}, "..."]]
//
// The first element of an array is the namespaced tag; the second
// (if a JsonObject) is the props map; the rest are children. Strings,
// numbers, and bools are text-like leaves. Everything else passes
// through opaquely so a renderer can decide how (or whether) to
// render unknown shapes.
//
// Mirrors the Swift `WunNode` in wun-ios. Conversion utilities
// (`fromJson` / `toJson`) bridge the JsonElement wire shape with
// the typed view-model used by renderers + the diff applicator.

package wun

import kotlinx.serialization.json.*

sealed class WunNode {
    object Null : WunNode()
    data class Text(val value: String) : WunNode()
    data class Number(val value: Double) : WunNode()
    data class Bool(val value: Boolean) : WunNode()
    data class Component(
        val tag: String,
        val props: Map<String, JsonElement>,
        val children: List<WunNode>,
    ) : WunNode()
    data class Opaque(val value: JsonElement) : WunNode()

    companion object {
        /** Materialise a JsonElement into the typed WunNode tree. */
        fun fromJson(json: JsonElement): WunNode = when (json) {
            is JsonNull -> Null
            is JsonPrimitive -> when {
                json.isString             -> Text(json.content)
                json.booleanOrNull != null -> Bool(json.boolean)
                json.longOrNull != null    -> Number(json.long.toDouble())
                json.doubleOrNull != null  -> Number(json.double)
                else                       -> Text(json.content)
            }
            is JsonObject -> Opaque(json)
            is JsonArray -> {
                val first = json.firstOrNull()
                if (first is JsonPrimitive && first.isString) {
                    var index = 1
                    var props: Map<String, JsonElement> = emptyMap()
                    val maybeProps = json.getOrNull(index)
                    if (maybeProps is JsonObject) {
                        props = maybeProps.toMap()
                        index += 1
                    }
                    val children = json.subList(index, json.size).map(::fromJson)
                    Component(first.content, props, children)
                } else Opaque(json)
            }
        }
    }

    /** Inverse of fromJson. */
    fun toJson(): JsonElement = when (this) {
        Null            -> JsonNull
        is Bool         -> JsonPrimitive(value)
        is Number       -> JsonPrimitive(value)
        is Text         -> JsonPrimitive(value)
        is Opaque       -> value
        is Component    -> buildJsonArray {
            add(JsonPrimitive(tag))
            if (props.isNotEmpty()) add(JsonObject(props))
            children.forEach { add(it.toJson()) }
        }
    }
}
