package app.sanafi.probe.core

/** The eight Phase 0 questions, in the order the owner runs them. */
enum class ProbeId { INSTALL, MIC, BOOT_LISTEN, ASSIST_BUTTON, CALL_LATENCY, ECHO, WAKE_OVER_MUSIC, TELEPHONY }

enum class Status { NOT_RUN, PASS, FAIL }

data class ProbeResult(
    val id: ProbeId,
    val status: Status,
    val detail: String,
    val numbers: Map<String, Double> = emptyMap(),
    val atEpochMs: Long,
)
