package app.sanafi.probe.core

import app.sanafi.probe.core.LatencyTimeline.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeReducerTest {
    private fun fullTimeline() = LatencyTimeline().apply {
        mark(Mark.TRIGGER, 1000)
        mark(Mark.TOKEN_RECEIVED, 1400)
        mark(Mark.ROOM_CONNECTED, 1900)
        mark(Mark.AGENT_JOINED, 2300)
        mark(Mark.AGENT_FIRST_SPEECH, 2800)
    }

    @Test
    fun `mic without permission fails and says so`() {
        val r = ProbeReducer.mic(permissionGranted = false, pcm = ShortArray(0), nowMs = 5)
        assertEquals(ProbeId.MIC, r.id)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("الصلاحية"))
        assertEquals(5L, r.atEpochMs)
    }

    @Test
    fun `mic that records only silence fails, never passes`() {
        val r = ProbeReducer.mic(true, ShortArray(16000), 5)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("صامت"))
    }

    @Test
    fun `mic with a real signal passes and reports its peak`() {
        val r = ProbeReducer.mic(true, shortArrayOf(16384, -16384), 5)
        assertEquals(Status.PASS, r.status)
        assertEquals(-6.02, r.numbers.getValue("peak_dbfs"), 0.01)
    }

    @Test
    fun `call refused by HUMAIN fails with the hint for that code`() {
        val r = ProbeReducer.call(LatencyTimeline(), "CREDITS_EXHAUSTED", 5)
        assertEquals(ProbeId.CALL_LATENCY, r.id)
        assertEquals(Status.FAIL, r.status)
        assertEquals(ErrorHints.forCode("CREDITS_EXHAUSTED"), r.detail)
    }

    @Test
    fun `call that never heard the agent fails and names where it stopped`() {
        val t = LatencyTimeline().apply { mark(Mark.TRIGGER, 0); mark(Mark.TOKEN_RECEIVED, 300) }
        val r = ProbeReducer.call(t, null, 5)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("TOKEN_RECEIVED"))
    }

    @Test
    fun `call that heard the agent passes with total and step times`() {
        val r = ProbeReducer.call(fullTimeline(), null, 5)
        assertEquals(Status.PASS, r.status)
        assertEquals(1800.0, r.numbers.getValue("total_ms"), 0.0)
        assertEquals(400.0, r.numbers.getValue("TRIGGER->TOKEN_RECEIVED"), 0.0)
        assertTrue(r.detail.contains("1800"))
    }

    @Test
    fun `a slow call still passes because the target is a number, not a gate`() {
        val t = LatencyTimeline().apply { mark(Mark.TRIGGER, 0); mark(Mark.AGENT_FIRST_SPEECH, 9000) }
        assertEquals(Status.PASS, ProbeReducer.call(t, null, 5).status)
    }

    @Test
    fun `echo passes when the agent was interrupted at most once`() {
        val r = ProbeReducer.echo(selfInterruptions = 1, agentTurns = 4, nowMs = 5)
        assertEquals(ProbeId.ECHO, r.id)
        assertEquals(Status.PASS, r.status)
        assertEquals(1.0, r.numbers.getValue("self_interruptions"), 0.0)
        assertEquals(4.0, r.numbers.getValue("agent_turns"), 0.0)
    }

    @Test
    fun `echo fails from the second self-interruption`() {
        assertEquals(Status.FAIL, ProbeReducer.echo(2, 4, 5).status)
    }

    @Test
    fun `echo cannot be judged when the agent never spoke`() {
        val r = ProbeReducer.echo(0, 0, 5)
        assertEquals(Status.FAIL, r.status)
        assertNotNull(r.detail)
        assertTrue(r.detail.isNotBlank())
    }
}
