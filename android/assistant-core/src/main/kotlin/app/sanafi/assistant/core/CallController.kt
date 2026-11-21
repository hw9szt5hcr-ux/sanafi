package app.sanafi.assistant.core

sealed interface CallEvent {
    data class Connected(val atMs: Long) : CallEvent

    /** The agent's `lk.agent.state`: initializing, listening, thinking or speaking. */
    data class AgentState(val state: String, val atMs: Long) : CallEvent
    data class LocalSpeaking(val atMs: Long) : CallEvent
    data class ToolFinished(val after: After, val atMs: Long) : CallEvent

    /** HUMAIN's `session_end` message on the `humain.session` topic. */
    data class SessionEnd(val reason: String) : CallEvent
    data object Disconnected : CallEvent

    /** A second button press, or the button on the call screen. */
    data object UserEnd : CallEvent
    data class Tick(val atMs: Long) : CallEvent
}

sealed interface Decision {
    data object Continue : Decision
    data class End(val reason: String) : Decision
}

/**
 * Decides when a call must end. The owner's HUMAIN plan sets no idle timeout and no maximum
 * duration, and every started minute is billed, so the app itself has to hang up.
 */
class CallController {
    var joinedAtMs: Long? = null
        private set

    private var ended: Decision.End? = null
    private var connectedAt: Long? = null
    private var agentState: String? = null
    private var idleSince: Long? = null
    private var toolEndedAt: Long? = null
    private var spokeAfterTool = false

    /** Once an End is returned, every later event returns the same End. */
    fun on(event: CallEvent): Decision {
        ended?.let { return it }
        val now: Long? = when (event) {
            is CallEvent.Connected -> event.atMs.also { connectedAt = it }
            is CallEvent.AgentState -> event.atMs.also { onAgentState(event.state, it) }
            is CallEvent.LocalSpeaking -> event.atMs.also { at -> if (idleSince != null) idleSince = at }
            is CallEvent.ToolFinished -> event.atMs.also { at ->
                if (event.after == After.END_CALL) {
                    toolEndedAt = at
                    spokeAfterTool = false
                }
            }
            is CallEvent.SessionEnd -> return end("agent:${event.reason}")
            CallEvent.Disconnected -> return end("disconnected")
            CallEvent.UserEnd -> return end("user")
            is CallEvent.Tick -> event.atMs
        }
        ended?.let { return it }
        return checkClocks(now ?: return Decision.Continue)
    }

    private fun onAgentState(state: String, at: Long) {
        if (joinedAtMs == null) joinedAtMs = at
        if (toolEndedAt != null) {
            if (state == "speaking") spokeAfterTool = true
            else if (state == "listening" && spokeAfterTool) end("tool_done")
        }
        if (state == "listening") {
            if (agentState != "listening") idleSince = at
        } else {
            idleSince = null
        }
        agentState = state
    }

    private fun checkClocks(now: Long): Decision {
        val connected = connectedAt
        return when {
            toolEndedAt?.let { now - it >= TOOL_END_CAP_MS } == true -> end("tool_done")
            connected != null && joinedAtMs == null && now - connected >= JOIN_TIMEOUT_MS -> end("agent_missing")
            idleSince?.let { now - it >= IDLE_MS } == true -> end("idle")
            connected != null && now - connected >= MAX_CALL_MS -> end("max_duration")
            else -> Decision.Continue
        }
    }

    private fun end(reason: String): Decision.End = ended ?: Decision.End(reason).also { ended = it }

    companion object {
        const val JOIN_TIMEOUT_MS = 10_000L
        const val IDLE_MS = 20_000L
        const val MAX_CALL_MS = 600_000L
        const val TOOL_END_CAP_MS = 4_000L
    }
}
