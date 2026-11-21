package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicWatchTest {
    private val silent = Double.NEGATIVE_INFINITY

    private fun log(
        peaks: List<Double>,
        silencedWindows: Int = 0,
        otherRecorderWindows: Int = 0,
        sanafiIsAssistant: Boolean = false,
    ) = MicWatchLog(windowMs = 500, windowPeaksDbfs = peaks, silencedWindows = silencedWindows, otherRecorderWindows = otherRecorderWindows, sanafiIsAssistant = sanafiIsAssistant)

    @Test
    fun `a microphone that opens midway passes and says when it opened and for how long`() {
        val peaks = List(10) { silent } + List(6) { -20.0 } + List(4) { -90.0 }
        val r = ProbeReducer.micWatch(permissionGranted = true, log = log(peaks, otherRecorderWindows = 8), nowMs = 7)
        assertEquals(ProbeId.MIC_WATCH, r.id)
        assertEquals(Status.PASS, r.status)
        assertEquals(10.0, r.numbers.getValue("watched_s"), 0.0)
        assertEquals(5.0, r.numbers.getValue("first_heard_s"), 0.0)
        assertEquals(8.0, r.numbers.getValue("last_heard_s"), 0.0)
        assertEquals(3.0, r.numbers.getValue("heard_s"), 0.0)
        assertEquals(-20.0, r.numbers.getValue("max_peak_dbfs"), 0.0)
        assertEquals(0.0, r.numbers.getValue("silenced_s"), 0.0)
        assertEquals(4.0, r.numbers.getValue("other_recorders_s"), 0.0)
        assertEquals(0.0, r.numbers.getValue("sanafi_is_assistant"), 0.0)
        assertTrue(r.detail.contains("انفتح"))
        assertEquals(7L, r.atEpochMs)
    }

    @Test
    fun `silence that Android itself imposed fails and says the system muted the recording`() {
        val r = ProbeReducer.micWatch(true, log(List(20) { silent }, silencedWindows = 20, sanafiIsAssistant = true), 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("النظام كاتم"))
        assertEquals(10.0, r.numbers.getValue("silenced_s"), 0.0)
        assertEquals(-120.0, r.numbers.getValue("max_peak_dbfs"), 0.0)
        assertEquals(1.0, r.numbers.getValue("sanafi_is_assistant"), 0.0)
        assertFalse(r.numbers.containsKey("first_heard_s"))
        assertEquals(r, ProbeCodec.decode(ProbeCodec.encode(r)))
    }

    @Test
    fun `silence with nothing muting it fails as a microphone that never opened`() {
        val r = ProbeReducer.micWatch(true, log(List(20) { -80.0 }), 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("ما انفتح"))
        assertFalse(r.detail.contains("النظام كاتم"))
        assertEquals(0.0, r.numbers.getValue("heard_s"), 0.0)
        assertEquals(-80.0, r.numbers.getValue("max_peak_dbfs"), 0.0)
    }

    @Test
    fun `a sound exactly at the threshold does not count as heard`() {
        val r = ProbeReducer.micWatch(true, log(listOf(-50.0, -50.0)), 7)
        assertEquals(Status.FAIL, r.status)
    }

    @Test
    fun `no permission fails with the permission message`() {
        val r = ProbeReducer.micWatch(false, log(emptyList()), 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("الصلاحية"))
    }

    @Test
    fun `a recorder that never produced a window fails as unable to open the microphone`() {
        val r = ProbeReducer.micWatch(true, log(emptyList()), 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("ما قدر"))
    }
}
