package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultTest {
    @Test
    fun `ids are the eight phase-0 questions in order`() {
        assertEquals(
            listOf("INSTALL", "MIC", "BOOT_LISTEN", "ASSIST_BUTTON", "MIC_WATCH", "CALL_LATENCY", "ECHO", "WAKE_OVER_MUSIC", "TELEPHONY"),
            ProbeId.values().map { it.name },
        )
    }

    @Test
    fun `a fresh result defaults to no numbers`() {
        assertTrue(ProbeResult(ProbeId.MIC, Status.PASS, "ok", atEpochMs = 1L).numbers.isEmpty())
    }
}
