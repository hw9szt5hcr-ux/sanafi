package app.sanafi.probe.core

/** Timestamps of one call, from the owner's trigger to the agent's first word. */
class LatencyTimeline {
    enum class Mark { TRIGGER, TOKEN_RECEIVED, ROOM_CONNECTED, AGENT_JOINED, AGENT_FIRST_SPEECH }

    private val times = mutableMapOf<Mark, Long>()

    /** Records when [m] first happened; later repeats of the same mark are ignored. */
    fun mark(m: Mark, atMs: Long) {
        times.putIfAbsent(m, atMs)
    }

    /** Time between consecutive marks, only where both ends were recorded. */
    fun deltas(): Map<String, Long> =
        Mark.values().toList().zipWithNext().mapNotNull { (from, to) ->
            val start = times[from] ?: return@mapNotNull null
            val end = times[to] ?: return@mapNotNull null
            "${from.name}->${to.name}" to (end - start)
        }.toMap()

    fun totalToFirstSpeechMs(): Long? {
        val start = times[Mark.TRIGGER] ?: return null
        val end = times[Mark.AGENT_FIRST_SPEECH] ?: return null
        return end - start
    }

    /** The furthest step the call reached, or null when nothing was recorded. */
    fun lastMark(): Mark? = Mark.values().lastOrNull { it in times }
}
