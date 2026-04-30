// Helpers shared by foundational renderers.

package wun.foundation

import wun.WunNode

object Children {
    /** Concatenate any string-like children into a single label.
     *  Useful for components like :wun/Text and :wun/Button that
     *  take a string body as their child. */
    fun flatten(children: List<WunNode>): String =
        children.mapNotNull { node ->
            when (node) {
                is WunNode.Text   -> node.value
                is WunNode.Number -> formatNumber(node.value)
                is WunNode.Bool   -> node.value.toString()
                else              -> null
            }
        }.joinToString("")

    private fun formatNumber(n: Double): String =
        if (n.rem(1.0) == 0.0 && kotlin.math.abs(n) < Long.MAX_VALUE.toDouble())
            n.toLong().toString()
        else n.toString()
}
