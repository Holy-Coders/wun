package wun

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffTest {

    private fun arr(vararg values: JsonElement) = JsonArray(values.toList())
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
    private fun s(value: String) = JsonPrimitive(value)
    private fun i(value: Int)    = JsonPrimitive(value)

    private fun replace(path: List<Int>, value: JsonElement) =
        Patch("replace", path, value)
    private fun insert(path: List<Int>, value: JsonElement) =
        Patch("insert", path, value)
    private fun remove(path: List<Int>) =
        Patch("remove", path)

    @Test fun replaceAtRoot() {
        val before = s("old")
        val after = Diff.apply(before, replace(emptyList(), s("new")))
        assertEquals(s("new"), after)
    }

    /** ["wun/Text", {"variant":"h1"}, "Counter: 0"] -> "Counter: 1" via path [0]. */
    @Test fun replaceTextLeaf() {
        val before = arr(s("wun/Text"), obj("variant" to s("h1")), s("Counter: 0"))
        val after = Diff.apply(before, replace(listOf(0), s("Counter: 1")))
        assertEquals(arr(s("wun/Text"), obj("variant" to s("h1")), s("Counter: 1")), after)
    }

    @Test fun replaceNested() {
        val before = arr(
            s("wun/Stack"), obj(),
            arr(s("wun/Text"),   obj(), s("x")),
            arr(s("wun/Button"), obj(), s("+"))
        )
        val after = Diff.apply(before, replace(listOf(0, 0), s("y")))
        assertEquals(arr(
            s("wun/Stack"), obj(),
            arr(s("wun/Text"),   obj(), s("y")),
            arr(s("wun/Button"), obj(), s("+"))
        ), after)
    }

    @Test fun insertAtEnd() {
        val before = arr(s("wun/Stack"), obj(), s("a"), s("b"))
        val after = Diff.apply(before, insert(listOf(2), s("c")))
        assertEquals(arr(s("wun/Stack"), obj(), s("a"), s("b"), s("c")), after)
    }

    @Test fun removeTrailingHighestFirst() {
        val before = arr(s("wun/Stack"), obj(), s("a"), s("b"), s("c"), s("d"))
        val patches = listOf(remove(listOf(3)), remove(listOf(2)))
        val after = Diff.apply(before, patches)
        assertEquals(arr(s("wun/Stack"), obj(), s("a"), s("b")), after)
    }

    @Test fun noPropsBranch() {
        val before = arr(s("wun/Stack"), s("a"), s("b"))
        val after = Diff.apply(before, replace(listOf(1), s("B")))
        assertEquals(arr(s("wun/Stack"), s("a"), s("B")), after)
    }

    @Test fun applyMany() {
        val value = arr(s("wun/Stack"), obj(), s("hi"))
        val patches = listOf(
            replace(emptyList(), value),
            replace(listOf(0), s("bye")),
        )
        val after = Diff.apply(JsonNull, patches)
        assertEquals(arr(s("wun/Stack"), obj(), s("bye")), after)
    }

    // ----- wire v2: keyed children ------------------------------------------

    private fun entry(key: String, existing: Boolean = true, value: JsonElement? = null) =
        ChildOrderEntry(s(key), existing, value)

    private fun children(path: List<Int>, order: List<ChildOrderEntry>) =
        Patch("children", path, value = null, order = order)

    @Test fun childrenOpReordersExisting() {
        val a = arr(s("wun/Text"), obj("key" to s("a")), s("A"))
        val b = arr(s("wun/Text"), obj("key" to s("b")), s("B"))
        val c = arr(s("wun/Text"), obj("key" to s("c")), s("C"))
        val before = arr(s("wun/Stack"), obj(), a, b, c)
        val after = Diff.apply(before,
            children(emptyList(), listOf(entry("c"), entry("a"), entry("b"))))
        assertEquals(arr(s("wun/Stack"), obj(), c, a, b), after)
    }

    @Test fun childrenOpInsertsInline() {
        val a = arr(s("wun/Text"), obj("key" to s("a")), s("A"))
        val bNew = arr(s("wun/Text"), obj("key" to s("b")), s("B!"))
        val before = arr(s("wun/Stack"), obj(), a)
        val after = Diff.apply(before,
            children(emptyList(), listOf(entry("a"), entry("b", existing = false, value = bNew))))
        assertEquals(arr(s("wun/Stack"), obj(), a, bNew), after)
    }

    @Test fun childrenOpThenRecursiveReplace() {
        val a1 = arr(s("wun/Text"), obj("key" to s("a")), s("A1"))
        val a2 = arr(s("wun/Text"), obj("key" to s("a")), s("A2"))
        val b  = arr(s("wun/Text"), obj("key" to s("b")), s("B"))
        val before = arr(s("wun/Stack"), obj(), a1, b)
        val patches = listOf(
            children(emptyList(), listOf(entry("b"), entry("a"))),
            replace(listOf(1, 0), s("A2"))
        )
        val after = Diff.apply(before, patches)
        assertEquals(arr(s("wun/Stack"), obj(), b, a2), after)
    }
}
