// JSON-Patch-flavoured ops the server emits over /wun.
//   {"op":"replace", "path":[],    "value": <tree>}
//   {"op":"insert",  "path":[2],   "value": <node>}
//   {"op":"remove",  "path":[0,1]}
//
// Path elements are integer child indices. `[]` addresses the whole
// tree. Hiccup vectors carry their tag at index 0 and an optional
// props map at index 1; child paths skip those slots.

package wun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class PatchOp {
    @SerialName("replace") Replace,
    @SerialName("insert")  Insert,
    @SerialName("remove")  Remove;

    companion object {
        fun of(s: String): PatchOp = when (s) {
            "replace" -> Replace
            "insert"  -> Insert
            "remove"  -> Remove
            else      -> error("unknown PatchOp: $s")
        }
    }
}

@Serializable
data class Patch(
    val op: String,
    val path: List<Int>,
    val value: JsonElement? = null,
) {
    val opEnum: PatchOp get() = PatchOp.of(op)
}
