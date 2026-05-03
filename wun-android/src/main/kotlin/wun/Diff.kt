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
        PatchOp.Replace  -> replace(tree, patch.path, patch.value ?: JsonNull)
        PatchOp.Insert   -> insert(tree,  patch.path, patch.value ?: JsonNull)
        PatchOp.Remove   -> remove(tree,  patch.path)
        PatchOp.Children -> children(tree, patch.path, patch.order ?: emptyList())
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

    /** Wire-v2 keyed-children topology op. Rebuild `path`'s children list
     *  to match `order`: existing entries are looked up in the current
     *  children by `:key` and reused; entries flagged `existing? = false`
     *  drop in their inline `value` instead. */
    private fun children(tree: JsonElement, path: List<Int>, order: List<ChildOrderEntry>): JsonElement {
        if (path.isEmpty()) return replayChildrenOrder(tree, order)
        return updateAtPath(tree, path) { node -> replayChildrenOrder(node, order) }
    }

    private fun replayChildrenOrder(tree: JsonElement, order: List<ChildOrderEntry>): JsonElement {
        if (tree !is JsonArray) return tree
        val offset = childOffset(tree)
        val byKey = mutableMapOf<String, JsonElement>()
        for (i in offset until tree.size) {
            keyOf(tree[i])?.let { byKey[it] = tree[i] }
        }
        val rebuilt = mutableListOf<JsonElement>().apply {
            addAll(tree.subList(0, offset))
        }
        for (entry in order) {
            val keyStr = jsonKeyAsString(entry.key) ?: continue
            if (entry.existing) {
                val existing = byKey[keyStr]
                if (existing != null) {
                    rebuilt.add(existing)
                } else if (entry.value != null) {
                    // Defensive: client thought it was existing but local
                    // tree lost it. Use the inline value if any.
                    rebuilt.add(entry.value)
                }
            } else if (entry.value != null) {
                rebuilt.add(entry.value)
            }
        }
        return JsonArray(rebuilt)
    }

    private fun keyOf(node: JsonElement): String? {
        if (node !is JsonArray || !hasProps(node)) return null
        val props = node[1] as? JsonObject ?: return null
        val keyJson = props["key"] ?: return null
        return jsonKeyAsString(keyJson)
    }

    private fun jsonKeyAsString(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> if (value.isString) value.content else value.content
        else             -> null
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
