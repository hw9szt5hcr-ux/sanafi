package app.sanafi.probe.core

import app.sanafi.probe.core.LatencyTimeline.Mark

/**
 * Follows the agent's `lk.agent.state` attribute (initializing, listening, thinking, speaking)
 * during one call: fills in the join and first-speech marks and counts how often the agent
 * started speaking and how often it started thinking.
 *
 * In a call where the owner stays silent, every thinking entry means the agent believed it
 * heard someone, which is how the echo probe detects the agent hearing itself.
 */
class AgentStateTracker(private val timeline: LatencyTimeline) {
    var agentTurns = 0
        private set
    var thinkingEntries = 0
        private set
    private var last: String? = null

    fun onState(state: String, atMs: Long) {
        if (state == last) return
        if (last == null) timeline.mark(Mark.AGENT_JOINED, atMs)
        when (state) {
            "speaking" -> {
                agentTurns++
                timeline.mark(Mark.AGENT_FIRST_SPEECH, atMs)
            }
            "thinking" -> thinkingEntries++
        }
        last = state
    }
}
