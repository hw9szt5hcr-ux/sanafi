package app.sanafi.assistant.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRouterTest {
    private fun router(vararg handlers: Pair<String, ToolHandler>) = ToolRouter(mapOf(*handlers)) { 0L }
    private val candidates = buildJsonObject { put("candidates", buildJsonArray { add("أ"); add("ب") }) }

    @Test
    fun `a handled tool gets its arguments and the reply carries the suggested sentence`() = runTest {
        var seen: JsonObject? = null
        val result = router("play_music" to ToolHandler { seen = it; ToolOutcome.Done(say = "أبشر", after = After.END_CALL) })
            .handle("""{"tool":"play_music","arguments":{"query":"محمد عبده"}}""")
        assertEquals("محمد عبده", ToolArgs.string(seen!!, "query"))
        assertEquals("""{"response":"أبشر"}""", result.replyJson)
        assertEquals("play_music", result.tool)
        assertEquals(After.END_CALL, (result.outcome as ToolOutcome.Done).after)
    }

    @Test
    fun `data and sentence are merged, data alone is sent as is, nothing at all is ok true`() = runTest {
        val both = router("t" to ToolHandler { ToolOutcome.Done(say = "أي واحد؟", data = candidates) }).handle("""{"tool":"t"}""")
        assertEquals(Json.parseToJsonElement("""{"response":"أي واحد؟","candidates":["أ","ب"]}"""), Json.parseToJsonElement(both.replyJson))
        val dataOnly = router("t" to ToolHandler { ToolOutcome.Done(data = candidates) }).handle("""{"tool":"t"}""")
        assertEquals(Json.parseToJsonElement("""{"candidates":["أ","ب"]}"""), Json.parseToJsonElement(dataOnly.replyJson))
        val nothing = router("t" to ToolHandler { ToolOutcome.Done() }).handle("""{"tool":"t"}""")
        assertEquals("""{"ok":true}""", nothing.replyJson)
    }

    @Test
    fun `an unknown tool, invalid JSON and a missing tool name all fail without throwing`() = runTest {
        for (payload in listOf("""{"tool":"fly","arguments":{}}""", "not json", """{"arguments":{}}""", "[]", """{"tool":7}""", "")) {
            val result = router().handle(payload)
            assertTrue(payload, result.outcome is ToolOutcome.Failed)
            assertEquals(false, Json.parseToJsonElement(result.replyJson).jsonObject.getValue("ok").toString().toBoolean())
            assertTrue(Json.parseToJsonElement(result.replyJson).jsonObject.containsKey("error"))
        }
    }

    @Test
    fun `missing or non-object arguments are treated as empty and a handler that throws becomes a failure`() = runTest {
        var seen: JsonObject? = null
        val handler = ToolHandler { seen = it; ToolOutcome.Done() }
        router("x" to handler).handle("""{"tool":"x"}""")
        assertEquals(JsonObject(emptyMap()), seen)
        seen = null
        router("x" to handler).handle("""{"tool":"x","arguments":"oops"}""")
        assertEquals(JsonObject(emptyMap()), seen)
        val thrown = router("x" to ToolHandler { error("boom") }).handle("""{"tool":"x"}""")
        assertTrue(thrown.outcome is ToolOutcome.Failed)
        assertEquals("x", thrown.tool)
        assertTrue(thrown.replyJson.startsWith("""{"ok":false"""))
    }

    @Test
    fun `wrong-typed and blank arguments read as null`() {
        val args = Json.parseToJsonElement("""{"query":5,"place":"  ","name":" خالد ","flag":true,"none":null}""").jsonObject
        assertNull(ToolArgs.string(args, "query"))
        assertNull(ToolArgs.string(args, "place"))
        assertNull(ToolArgs.string(args, "flag"))
        assertNull(ToolArgs.string(args, "none"))
        assertNull(ToolArgs.string(args, "missing"))
        assertEquals("خالد", ToolArgs.string(args, "name"))
    }

    @Test
    fun `an oversized reply is replaced by a short failure`() = runTest {
        val huge = buildJsonObject { put("text", "م".repeat(20_000)) }
        val result = router("t" to ToolHandler { ToolOutcome.Done(data = huge) }).handle("""{"tool":"t"}""")
        assertTrue(result.replyJson.toByteArray(Charsets.UTF_8).size < 200)
        assertTrue(result.replyJson.startsWith("""{"ok":false"""))
    }

    @Test
    fun `the time a tool took is measured with the injected clock`() = runTest {
        val times = ArrayDeque(listOf(100L, 340L))
        val result = ToolRouter(mapOf("t" to ToolHandler { ToolOutcome.Done() })) { times.removeFirst() }.handle("""{"tool":"t"}""")
        assertEquals(240L, result.tookMs)
    }
}
