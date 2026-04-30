// Path-aware patch applicator. Kotlin port of the shared cljc
// `wun.diff/apply-patches` and the Swift `Diff` -- same Hiccup-aware
// indexing, same :replace / :insert / :remove ops, same path
// semantics. Producer (server) and all three consumers (web, iOS,
// Android) implement the same algorithm; the implementations have
// to stay step for step.

package wun

import kotlinx.serialization.json.*

object Diff {

    // MARK: - Hiccup helpers

    private fun hasProps(arr: JsonArray): Boolean {
        if (arr.size < 2) return false
        return arr[1] is JsonObject
    }

    private fun childOffset(arr: JsonArray): Int = if (hasProps(arr)) 2 else 1

    // MARK: - Public apply

    fun apply(tree: JsonElement, patch: Patch): JsonElement = when (patch.opEnum) {
        PatchOp.Replace -> replace(tree, patch.path, patch.value ?: JsonNull)
        PatchOp.Insert  -> insert(tree,  patch.path, patch.value ?: JsonNull)
        PatchOp.Remove  -> remove(tree,  patch.path)
    }

    fun apply(tree: JsonElement, patches: List<Patch>): JsonElement =
        patches.fold(tree) { t, p -> apply(t, p) }

    // MARK: - Op implementations

    private fun replace(tree: JsonElement, path: List<Int>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val parent = path.dropLast(1)
        val idx = path.last()
        return updateAtPath(tree, parent) { node -> replaceChild(node, idx, value) }
    }

    private fun insert(tree: JsonElement, path: List<Int>, value: JsonElement): JsonElement {
        require(path.isNotEmpty()) { "insert requires non-empty path" }
        val parent = path.dropLast(1)
        val idx = path.last()
        return updateAtPath(tree, parent) { node -> insertChild(node, idx, value) }
    }

    private fun remove(tree: JsonElement, path: List<Int>): JsonElement {
        require(path.isNotEmpty()) { "remove requires non-empty path" }
        val parent = path.dropLast(1)
        val idx = path.last()
        return updateAtPath(tree, parent) { node -> removeChild(node, idx) }
    }

    // MARK: - Path navigation

    private fun updateAtPath(
        tree: JsonElement,
        path: List<Int>,
        fn: (JsonElement) -> JsonElement,
    ): JsonElement {
        if (path.isEmpty()) return fn(tree)
        if (tree !is JsonArray) return tree
        val offset = childOffset(tree)
        val head = path[0]
        val rest = path.drop(1)
        val absIdx = head + offset
        if (absIdx >= tree.size) return tree
        val mutated = tree.toMutableList()
        mutated[absIdx] = updateAtPath(tree[absIdx], rest, fn)
        return JsonArray(mutated)
    }

    // MARK: - Child mutators

    private fun replaceChild(tree: JsonElement, i: Int, value: JsonElement): JsonElement {
        if (tree !is JsonArray) return tree
        val absIdx = i + childOffset(tree)
        if (absIdx >= tree.size) return tree
        val mutated = tree.toMutableList()
        mutated[absIdx] = value
        return JsonArray(mutated)
    }

    private fun insertChild(tree: JsonElement, i: Int, value: JsonElement): JsonElement {
        if (tree !is JsonArray) return tree
        val absIdx = minOf(i + childOffset(tree), tree.size)
        val mutated = tree.toMutableList()
        mutated.add(absIdx, value)
        return JsonArray(mutated)
    }

    private fun removeChild(tree: JsonElement, i: Int): JsonElement {
        if (tree !is JsonArray) return tree
        val absIdx = i + childOffset(tree)
        if (absIdx >= tree.size) return tree
        val mutated = tree.toMutableList()
        mutated.removeAt(absIdx)
        return JsonArray(mutated)
    }
}
