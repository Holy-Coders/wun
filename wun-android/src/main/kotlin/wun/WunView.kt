// Compose driver for a Wun tree. Given a WunNode and a Registry,
// emit a Composable. Recursive: components return composable
// children which can themselves contain further WunViews.
//
// The registry is read from CompositionLocal (default
// Registry.shared) so renderers like WunStack -- which build
// children with `WunView(kid)` and no explicit registry -- still
// pick up the same registry the host wired up at the top of the
// composition tree.

package wun

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalWunRegistry = compositionLocalOf { Registry.shared }

@Composable
fun WunView(node: WunNode, registry: Registry? = null) {
    if (registry != null && registry !== LocalWunRegistry.current) {
        CompositionLocalProvider(LocalWunRegistry provides registry) {
            WunViewBody(node, registry)
        }
    } else {
        WunViewBody(node, LocalWunRegistry.current)
    }
}

@Composable
private fun WunViewBody(node: WunNode, registry: Registry) {
    when (node) {
        is WunNode.Null      -> Unit
        is WunNode.Text      -> Text(node.value)
        is WunNode.Number    -> Text(formatNumber(node.value))
        is WunNode.Bool      -> Text(node.value.toString())
        is WunNode.Opaque    -> Unit
        is WunNode.Component -> {
            val render = registry.lookup(node.tag)
            if (render != null) {
                render(node.props, node.children)
            } else {
                Text("[unknown: ${node.tag}]", color = MaterialTheme.colors.error)
            }
        }
    }
}

private fun formatNumber(n: Double): String =
    if (n.rem(1.0) == 0.0 && kotlin.math.abs(n) < Long.MAX_VALUE.toDouble())
        n.toLong().toString()
    else n.toString()
