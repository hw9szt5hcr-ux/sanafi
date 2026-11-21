package app.sanafi.probe.core

import app.sanafi.probe.core.LatencyTimeline.Mark
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStateTrackerTest {
    @Test
    fun `the first state marks the agent joining and the first speaking marks first speech`() {
        val timeline = LatencyTimeline().apply { mark(Mark.TRIGGER, 0) }
        val tracker = AgentStateTracker(timeline)
        tracker.onState("initializing", 900)
        tracker.onState("listening", 1100)
        tracker.onState("speaking", 1600)
        assertEquals(1600L, timeline.totalToFirstSpeechMs())
        assertEquals(Mark.AGENT_FIRST_SPEECH, timeline.lastMark())
        assertEquals(1, tracker.agentTurns)
        assertEquals(0, tracker.thinkingEntries)
    }

    @Test
    fun `an agent that is already speaking when first seen counts as joined and speaking`() {
        val timeline = LatencyTimeline().apply { mark(Mark.TRIGGER, 0) }
        AgentStateTracker(timeline).onState("speaking", 700)
        assertEquals(700L, timeline.totalToFirstSpeechMs())
        assertEquals(mapOf("AGENT_JOINED->AGENT_FIRST_SPEECH" to 0L), timeline.deltas())
    }

    @Test
    fun `the same state reported twice is one entry`() {
        val tracker = AgentStateTracker(LatencyTimeline())
        tracker.onState("speaking", 1)
        tracker.onState("speaking", 2)
        tracker.onState("thinking", 3)
        tracker.onState("thinking", 4)
        assertEquals(1, tracker.agentTurns)
        assertEquals(1, tracker.thinkingEntries)
    }

    @Test
    fun `turns and thinking entries are counted across a conversation`() {
        val tracker = AgentStateTracker(LatencyTimeline())
        listOf("speaking", "listening", "thinking", "speaking", "listening", "thinking", "speaking", "listening")
            .forEachIndexed { i, state -> tracker.onState(state, i.toLong()) }
        assertEquals(3, tracker.agentTurns)
        assertEquals(2, tracker.thinkingEntries)
    }
}
