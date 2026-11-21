package app.sanafi.assistant.core

import app.sanafi.assistant.core.CallEvent.AgentState
import app.sanafi.assistant.core.CallEvent.Connected
import app.sanafi.assistant.core.CallEvent.Disconnected
import app.sanafi.assistant.core.CallEvent.LocalSpeaking
import app.sanafi.assistant.core.CallEvent.SessionEnd
import app.sanafi.assistant.core.CallEvent.Tick
import app.sanafi.assistant.core.CallEvent.ToolFinished
import app.sanafi.assistant.core.CallEvent.UserEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallControllerTest {
    private val go = Decision.Continue
    private fun end(reason: String) = Decision.End(reason)

    /** Feeds the events and returns the decision after each. */
    private fun run(vararg events: CallEvent): List<Decision> = CallController().let { c -> events.map(c::on) }

    @Test
    fun `the agent ending the session and the user ending it are reported with their reasons`() {
        assertEquals(end("agent:agent_ended"), run(Connected(0), SessionEnd("agent_ended")).last())
        assertEquals(end("user"), run(Connected(0), UserEnd).last())
        assertEquals(end("user"), run(UserEnd).last())
    }

    @Test
    fun `a room that closes without a session end message ends the call as disconnected`() {
        assertEquals(listOf(go, go, end("disconnected")), run(Connected(0), AgentState("listening", 500), Disconnected))
    }

    @Test
    fun `an agent that never shows up ends the call after ten seconds`() {
        assertEquals(listOf(go, go, end("agent_missing")), run(Connected(0), Tick(9_999), Tick(10_000)))
    }

    @Test
    fun `twenty seconds of listening with nobody speaking ends the call as idle`() {
        assertEquals(
            listOf(go, go, go, go, end("idle")),
            run(Connected(0), AgentState("speaking", 1_000), AgentState("listening", 3_000), Tick(22_999), Tick(23_000)),
        )
    }

    @Test
    fun `the driver speaking restarts the idle clock`() {
        assertEquals(
            listOf(go, go, go, go, end("idle")),
            run(Connected(0), AgentState("listening", 3_000), LocalSpeaking(20_000), Tick(39_999), Tick(40_000)),
        )
    }

    @Test
    fun `thinking or speaking never counts as idle`() {
        assertEquals(go, run(Connected(0), AgentState("listening", 1_000), AgentState("thinking", 5_000), Tick(60_000)).last())
        assertEquals(go, run(Connected(0), AgentState("speaking", 1_000), Tick(60_000)).last())
    }

    @Test
    fun `after a tool that ends the call, the call ends when the agent finishes its sentence`() {
        assertEquals(
            listOf(go, go, go, go, end("tool_done")),
            run(Connected(0), AgentState("thinking", 9_000), ToolFinished(After.END_CALL, 10_000), AgentState("speaking", 10_300), AgentState("listening", 11_200)),
        )
    }

    @Test
    fun `listening again before the agent has said its word does not end the call early`() {
        assertEquals(
            listOf(go, go, go, go, go, end("tool_done")),
            run(Connected(0), AgentState("thinking", 9_000), ToolFinished(After.END_CALL, 10_000), AgentState("listening", 10_100), AgentState("speaking", 10_300), AgentState("listening", 11_000)),
        )
    }

    @Test
    fun `if the agent says nothing after such a tool the call still ends four seconds later`() {
        assertEquals(
            listOf(go, go, go, go, end("tool_done")),
            run(Connected(0), AgentState("thinking", 9_000), ToolFinished(After.END_CALL, 10_000), Tick(13_999), Tick(14_000)),
        )
    }

    @Test
    fun `a tool that keeps talking does not end the call`() {
        assertEquals(
            go,
            run(Connected(0), AgentState("thinking", 9_000), ToolFinished(After.KEEP_TALKING, 10_000), AgentState("speaking", 10_300), AgentState("listening", 11_000), Tick(15_000)).last(),
        )
    }

    @Test
    fun `ten minutes is the hard limit`() {
        val controller = CallController()
        controller.on(Connected(0))
        controller.on(AgentState("speaking", 1_000))
        assertEquals(go, controller.on(Tick(599_999)))
        assertEquals(end("max_duration"), controller.on(Tick(600_000)))
    }

    @Test
    fun `the first reason sticks`() {
        assertEquals(listOf(end("user"), end("user"), end("user")), run(UserEnd, SessionEnd("agent_ended"), Tick(999_999)))
    }

    @Test
    fun `joinedAtMs is the first agent state seen`() {
        val controller = CallController()
        controller.on(Connected(0))
        assertNull(controller.joinedAtMs)
        controller.on(AgentState("initializing", 700))
        controller.on(AgentState("listening", 900))
        assertEquals(700L, controller.joinedAtMs)
    }
}

class DiagLogTest {
    @Test
    fun `the report lists entries oldest first under its header and keeps only the newest when full`() {
        val log = DiagLog(capacity = 3)
        for (i in 1..4) log.add(i * 10L, "tag$i", "text $i")
        assertEquals("SANAFI REPORT\n20 tag2 text 2\n30 tag3 text 3\n40 tag4 text 4", log.render("SANAFI REPORT"))
    }

    @Test
    fun `line breaks in an entry cannot break the report and the log survives encode and decode`() {
        val log = DiagLog()
        log.add(5, "tool", "name=play_music\nok\ttrue")
        log.add(9, "end", "user")
        assertEquals("H\n5 tool name=play_music ok true\n9 end user", log.render("H"))
        assertEquals(log.render("H"), DiagLog.decode(log.encode()).render("H"))
        assertEquals("H", DiagLog.decode("garbage without tabs").render("H"))
    }
}
