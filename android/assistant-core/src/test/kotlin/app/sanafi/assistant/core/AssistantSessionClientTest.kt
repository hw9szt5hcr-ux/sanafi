package app.sanafi.assistant.core

import app.sanafi.probe.net.HttpPoster
import app.sanafi.probe.net.SessionError
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantSessionClientTest {
    private class Recorder(private val status: Int, private val body: String, private val fail: Boolean = false) : HttpPoster {
        var url = ""; var headers = emptyMap<String, String>(); var json = ""
        override suspend fun post(url: String, headers: Map<String, String>, json: String): Pair<Int, String> {
            this.url = url; this.headers = headers; this.json = json
            if (fail) throw IOException("down")
            return status to body
        }
    }

    private val ask = SessionAsk(deviceId = "sanafi-1", voiceLabel = "mul_abdullah", voiceId = "v1", memory = "اسمه عبدالله", apps = listOf("Maps"), talkFirst = false)
    private val grantBody = """{"server_url":"wss://lk","participant_token":"tok","session_id":"as_1","voice_id":"v9"}"""

    @Test
    fun `the request carries the assistant mode, memory, apps and the cached voice id`() = runTest {
        val http = Recorder(200, grantBody)
        AssistantSessionClient(http).start("https://x.app/", "s3cret", ask)
        val sent = Json.parseToJsonElement(http.json).jsonObject
        assertEquals("https://x.app/api/agent-session", http.url)
        assertEquals("s3cret", http.headers["x-device-secret"])
        assertEquals("application/json", http.headers["Content-Type"])
        assertEquals("assistant", sent.getValue("mode").jsonPrimitive.content)
        assertEquals("sanafi-1", sent.getValue("device_id").jsonPrimitive.content)
        assertEquals("mul_abdullah", sent.getValue("voice_label").jsonPrimitive.content)
        assertEquals("v1", sent.getValue("voice_id").jsonPrimitive.content)
        assertEquals("اسمه عبدالله", sent.getValue("memory").jsonPrimitive.content)
        assertEquals(listOf("Maps"), sent.getValue("apps").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", sent.getValue("talk_first").toString())
    }

    @Test
    fun `no voice id key is sent when none is cached, and talk first is passed on`() = runTest {
        val http = Recorder(200, grantBody)
        AssistantSessionClient(http).start("https://x.app", "s", ask.copy(voiceId = null, talkFirst = true))
        val sent = Json.parseToJsonElement(http.json).jsonObject
        assertFalse(sent.containsKey("voice_id"))
        assertEquals("true", sent.getValue("talk_first").toString())
    }

    @Test
    fun `a grant is read with its voice id, and without one`() = runTest {
        val grant = AssistantSessionClient(Recorder(200, grantBody)).start("https://x.app", "s", ask).getOrThrow()
        assertEquals(AssistantGrant("wss://lk", "tok", "as_1", "v9"), grant)
        val bare = AssistantSessionClient(Recorder(200, """{"server_url":"wss://lk","participant_token":"tok","session_id":"as_1"}""")).start("https://x.app", "s", ask).getOrThrow()
        assertNull(bare.voiceId)
    }

    @Test
    fun `failures map to the shared error codes`() = runTest {
        suspend fun code(http: HttpPoster) = (AssistantSessionClient(http).start("https://x.app", "s", ask).exceptionOrNull() as SessionError).code
        assertEquals("network", code(Recorder(0, "", fail = true)))
        assertEquals("bad_secret", code(Recorder(401, """{"error":"bad_secret"}""")))
        assertEquals("CREDITS_EXHAUSTED", code(Recorder(502, """{"error":"humain","code":"CREDITS_EXHAUSTED"}""")))
        assertEquals("HTTP_500", code(Recorder(500, "oops")))
        assertEquals("HTTP_200_BAD_BODY", code(Recorder(200, "{}")))
    }
}
