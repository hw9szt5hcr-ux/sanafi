package app.sanafi.probe.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionClientTest {
    private val grantJson =
        """{"server_url":"wss://x","participant_token":"t","session_id":"as_1","expires_at":"z","limits":{"max_duration_s":900,"idle_timeout_s":null}}"""

    private fun codeOf(result: Result<SessionGrant>): String = (result.exceptionOrNull() as SessionError).code

    @Test
    fun `a 200 reply becomes a grant`() = runTest {
        val client = SessionClient { _, _, _ -> 200 to grantJson }
        assertEquals(SessionGrant("wss://x", "t", "as_1"), client.start("https://b", "s", "d", null, null).getOrThrow())
    }

    @Test
    fun `the request carries the secret header, device id, voice and mode`() = runTest {
        var seen: Triple<String, Map<String, String>, String>? = null
        SessionClient { u, h, j -> seen = Triple(u, h, j); 401 to "{}" }.start("https://b/", "s3", "dev", "mul_abdullah", "echo")
        assertEquals("https://b/api/agent-session", seen!!.first)
        assertEquals("s3", seen!!.second["x-device-secret"])
        assertEquals("application/json", seen!!.second["Content-Type"])
        assertEquals("""{"device_id":"dev","voice_label":"mul_abdullah","mode":"echo"}""", seen!!.third)
    }

    @Test
    fun `voice and mode are left out when not chosen`() = runTest {
        var body = ""
        SessionClient { _, _, j -> body = j; 401 to "{}" }.start("https://b", "s", "dev", null, null)
        assertEquals("""{"device_id":"dev"}""", body)
    }

    @Test
    fun `a wrong secret maps to bad_secret`() = runTest {
        assertEquals("bad_secret", codeOf(SessionClient { _, _, _ -> 401 to "{}" }.start("b", "s", "d", null, null)))
    }

    @Test
    fun `a HUMAIN refusal keeps HUMAIN's code`() = runTest {
        val client = SessionClient { _, _, _ -> 502 to """{"error":"humain","code":"CREDITS_EXHAUSTED"}""" }
        assertEquals("CREDITS_EXHAUSTED", codeOf(client.start("b", "s", "d", null, null)))
    }

    @Test
    fun `no connection maps to network`() = runTest {
        assertEquals("network", codeOf(SessionClient { _, _, _ -> throw IOException("x") }.start("b", "s", "d", null, null)))
    }

    @Test
    fun `an unexpected status or unreadable body is reported with its status`() = runTest {
        assertEquals("HTTP_500", codeOf(SessionClient { _, _, _ -> 500 to "<html>" }.start("b", "s", "d", null, null)))
        assertEquals("HTTP_200_BAD_BODY", codeOf(SessionClient { _, _, _ -> 200 to "{}" }.start("b", "s", "d", null, null)))
    }

    @Test
    fun `each start asks the backend again, a grant is never reused`() = runTest {
        var calls = 0
        val client = SessionClient { _, _, _ ->
            calls++
            200 to """{"server_url":"w","participant_token":"t$calls","session_id":"s","expires_at":"z","limits":{}}"""
        }
        val first = client.start("b", "s", "d", null, null).getOrThrow()
        val second = client.start("b", "s", "d", null, null).getOrThrow()
        assertEquals(2, calls)
        assertTrue(first.participantToken != second.participantToken)
    }
}
