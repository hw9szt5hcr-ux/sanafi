package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformReducerTest {
    private val speech = shortArrayOf(16384, -16384)
    private val silence = ShortArray(16000)

    @Test
    fun `listening that started from boot and heard sound passes`() {
        val r = ProbeReducer.bootListen("service", fromBoot = true, permissionGranted = true, pcm = speech, secondsAfterBoot = 41.0, previous = null, nowMs = 7)
        assertEquals(ProbeId.BOOT_LISTEN, r.id)
        assertEquals(Status.PASS, r.status)
        assertEquals(1.0, r.numbers.getValue("service_from_boot"), 0.0)
        assertEquals(-6.02, r.numbers.getValue("service_peak_dbfs"), 0.01)
        assertEquals(41.0, r.numbers.getValue("service_seconds_after_boot"), 0.0)
    }

    @Test
    fun `started from boot but silent fails and stores a finite level`() {
        val r = ProbeReducer.bootListen("service", true, true, silence, 41.0, null, 7)
        assertEquals(Status.FAIL, r.status)
        assertEquals(-120.0, r.numbers.getValue("service_peak_dbfs"), 0.0)
        assertTrue(r.detail.contains("صامت"))
        assertEquals(r, ProbeCodec.decode(ProbeCodec.encode(r)))
    }

    @Test
    fun `a manual start with sound does not count as starting from boot`() {
        val r = ProbeReducer.bootListen("service", fromBoot = false, permissionGranted = true, pcm = speech, secondsAfterBoot = 900.0, previous = null, nowMs = 7)
        assertEquals(Status.FAIL, r.status)
        assertEquals(0.0, r.numbers.getValue("service_from_boot"), 0.0)
        assertTrue(r.detail.contains("أعد تشغيل"))
    }

    @Test
    fun `missing microphone permission fails and says so`() {
        val r = ProbeReducer.bootListen("service", true, permissionGranted = false, pcm = ShortArray(0), secondsAfterBoot = 41.0, previous = null, nowMs = 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("الصلاحية"))
    }

    @Test
    fun `the assistant path can pass after the plain service failed, keeping both readings`() {
        val failed = ProbeReducer.bootListen("service", true, true, silence, 41.0, null, 7)
        val r = ProbeReducer.bootListen("assistant", true, true, speech, 44.0, failed, 8)
        assertEquals(Status.PASS, r.status)
        assertEquals(-120.0, r.numbers.getValue("service_peak_dbfs"), 0.0)
        assertEquals(-6.02, r.numbers.getValue("assistant_peak_dbfs"), 0.01)
        assertTrue(r.detail.contains("assistant"))
    }

    @Test
    fun `a later silent reading does not erase an earlier pass from another path`() {
        val passed = ProbeReducer.bootListen("assistant", true, true, speech, 44.0, null, 7)
        val r = ProbeReducer.bootListen("service", true, true, silence, 41.0, passed, 8)
        assertEquals(Status.PASS, r.status)
    }

    @Test
    fun `every assistant launch passes, names how it arrived and counts up`() {
        val first = ProbeReducer.assist("android.intent.action.ASSIST", previous = null, nowMs = 7)
        assertEquals(ProbeId.ASSIST_BUTTON, first.id)
        assertEquals(Status.PASS, first.status)
        assertEquals(1.0, first.numbers.getValue("launch_count"), 0.0)
        assertTrue(first.detail.contains("android.intent.action.ASSIST"))
        val second = ProbeReducer.assist("android.intent.action.VOICE_COMMAND", first, 8)
        assertEquals(2.0, second.numbers.getValue("launch_count"), 0.0)
        assertTrue(second.detail.contains("VOICE_COMMAND"))
    }

    @Test
    fun `a box without phone hardware fails telephony`() {
        val r = ProbeReducer.telephony(hasTelephony = false, simReady = false, canResolveCall = false, rang = null, nowMs = 7)
        assertEquals(ProbeId.TELEPHONY, r.id)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("ما يدعم"))
    }

    @Test
    fun `a SIM that is not ready fails telephony`() {
        val r = ProbeReducer.telephony(hasTelephony = true, simReady = false, canResolveCall = true, rang = null, nowMs = 7)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("الشريحة"))
    }

    @Test
    fun `capable hardware is undecided until a test call was placed`() {
        val r = ProbeReducer.telephony(true, true, true, rang = null, nowMs = 7)
        assertEquals(Status.NOT_RUN, r.status)
        assertEquals(1.0, r.numbers.getValue("has_telephony"), 0.0)
    }

    @Test
    fun `the owner's answer to did-it-ring decides telephony`() {
        assertEquals(Status.PASS, ProbeReducer.telephony(true, true, true, rang = true, nowMs = 7).status)
        val no = ProbeReducer.telephony(true, true, true, rang = false, nowMs = 7)
        assertEquals(Status.FAIL, no.status)
        assertEquals(0.0, no.numbers.getValue("rang"), 0.0)
    }

    @Test
    fun `report numbers are rounded to one decimal`() {
        val r = ProbeResult(ProbeId.MIC, Status.PASS, "", mapOf("peak_dbfs" to -6.0205999), atEpochMs = 0)
        assertTrue(ReportFormatter.format(listOf(r), "d").contains("peak_dbfs=-6.0"))
        assertTrue(!ReportFormatter.format(listOf(r), "d").contains("-6.02"))
    }
}
