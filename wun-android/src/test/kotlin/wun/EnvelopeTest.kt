package wun

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {
    private val initialFrame = """
        {
          "patches": [
            {
              "op": "replace",
              "path": [],
              "value": [
                "wun/Stack",
                {"gap": 12, "padding": 24},
                ["wun/Text", {"variant": "h1"}, "Counter: 0"],
                ["wun/Stack", {"direction": "row", "gap": 8},
                  ["wun/Button", {"on-press": {"intent": "counter/dec", "params": {}}}, "-"],
                  ["wun/Button", {"on-press": {"intent": "counter/inc", "params": {}}}, "+"]
                ]
              ]
            }
          ],
          "status": "ok",
          "state":  {"counter": 0},
          "resolves-intent": null
        }
    """.trimIndent()

    @Test fun decodesInitialFrame() {
        val env = Envelope.decode(initialFrame)
        assertEquals("ok", env.status)
        assertEquals(1, env.patches.size)
        assertEquals(PatchOp.Replace, env.patches[0].opEnum)
        assertEquals(emptyList(), env.patches[0].path)
        assertNull(env.resolvesIntent)
        val state = env.state as JsonObject
        assertEquals(0, state["counter"]!!.jsonPrimitive.int)
    }

    @Test fun materialisesWunNodeShape() {
        val env = Envelope.decode(initialFrame)
        val root = WunNode.fromJson(env.patches[0].value!!)
        assertTrue(root is WunNode.Component)
        root as WunNode.Component
        assertEquals("wun/Stack", root.tag)
        assertEquals(2, root.children.size)

        val text = root.children[0] as WunNode.Component
        assertEquals("wun/Text", text.tag)
        assertEquals("h1", text.props["variant"]!!.jsonPrimitive.content)
        val textKid = text.children[0] as WunNode.Text
        assertEquals("Counter: 0", textKid.value)
    }

    @Test fun roundTripsThroughJson() {
        val env = Envelope.decode(initialFrame)
        val raw = env.patches[0].value!!
        val node = WunNode.fromJson(raw)
        assertEquals(raw, node.toJson())
    }

    // ----- Wire v2 fields ---------------------------------------------------

    @Test fun decodesEnvelopeVersionAndCsrfAndThemeAndResync() {
        val frame = """
            {
              "patches": [],
              "status": "ok",
              "envelope-version": 2,
              "csrf-token": "tok-X",
              "resync?": true,
              "theme": {"wun.color/primary": "#0a66c2", "wun.spacing/md": 16}
            }
        """.trimIndent()
        val env = Envelope.decode(frame)
        assertEquals(2, env.envelopeVersion)
        assertEquals("tok-X", env.csrfToken)
        assertEquals(true, env.resync)
        val theme = env.theme as JsonObject
        assertEquals("#0a66c2", theme["wun.color/primary"]!!.jsonPrimitive.content)
        assertEquals(16, theme["wun.spacing/md"]!!.jsonPrimitive.int)
    }
}
