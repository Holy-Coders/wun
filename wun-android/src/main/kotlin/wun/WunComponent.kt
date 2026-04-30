// Compose renderer signature for the registry. WunComponent is a
// `@Composable` function of (props, children) that emits the
// component's content directly into the surrounding composition.
//
// Mirrors the Swift `typealias WunComponent = (props, children) ->
// AnyView`. The Compose annotation is part of the type, so the
// registry's `lookup` returns a `@Composable` function and call
// sites must already be in a composable scope -- which `WunView`
// is.

package wun

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonElement

typealias WunComponent = @Composable (
    props: Map<String, JsonElement>,
    children: List<WunNode>,
) -> Unit
