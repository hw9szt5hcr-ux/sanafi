package app.sanafi.probe.core

import app.sanafi.probe.core.LatencyTimeline.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTimelineTest {
    @Test
    fun `a full call reports total and per-step times`() {
        val t = LatencyTimeline()
        t.mark(Mark.TRIGGER, 1000)
        t.mark(Mark.TOKEN_RECEIVED, 1400)
        t.mark(Mark.ROOM_CONNECTED, 1900)
        t.mark(Mark.AGENT_JOINED, 2300)
        t.mark(Mark.AGENT_FIRST_SPEECH, 2800)
        assertEquals(1800L, t.totalToFirstSpeechMs())
        assertEquals(
            mapOf(
                "TRIGGER->TOKEN_RECEIVED" to 400L,
                "TOKEN_RECEIVED->ROOM_CONNECTED" to 500L,
                "ROOM_CONNECTED->AGENT_JOINED" to 400L,
                "AGENT_JOINED->AGENT_FIRST_SPEECH" to 500L,
            ),
            t.deltas(),
        )
    }

    @Test
    fun `a call that never reached first speech has no total`() {
        val t = LatencyTimeline()
        t.mark(Mark.TRIGGER, 1000)
        assertNull(t.totalToFirstSpeechMs())
        assertTrue(t.deltas().isEmpty())
    }

    @Test
    fun `an empty timeline has no total and no last mark`() {
        val t = LatencyTimeline()
        assertNull(t.totalToFirstSpeechMs())
        assertNull(t.lastMark())
    }

    @Test
    fun `the first time a mark is seen wins`() {
        val t = LatencyTimeline()
        t.mark(Mark.TRIGGER, 0)
        t.mark(Mark.AGENT_FIRST_SPEECH, 1500)
        t.mark(Mark.AGENT_FIRST_SPEECH, 9000)
        assertEquals(1500L, t.totalToFirstSpeechMs())
    }

    @Test
    fun `a skipped step leaves a gap instead of a wrong delta`() {
        val t = LatencyTimeline()
        t.mark(Mark.TRIGGER, 0)
        t.mark(Mark.ROOM_CONNECTED, 700)
        t.mark(Mark.AGENT_JOINED, 900)
        assertEquals(mapOf("ROOM_CONNECTED->AGENT_JOINED" to 200L), t.deltas())
        assertEquals(Mark.AGENT_JOINED, t.lastMark())
    }
}
