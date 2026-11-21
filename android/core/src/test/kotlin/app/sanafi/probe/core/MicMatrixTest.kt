package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicMatrixTest {
    private val silent = Double.NEGATIVE_INFINITY

    @Test
    fun `one source that hears sound makes the microphone pass and is named`() {
        val r = ProbeReducer.micMatrix(
            permissionGranted = true,
            readings = listOf(MicReading("mic", silent), MicReading("voice_communication", -18.5), MicReading("camcorder", -70.0)),
            inputDevices = listOf("OttoAibox-P3 Pro type=15"),
            nowMs = 9,
        )
        assertEquals(ProbeId.MIC, r.id)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.detail.contains("voice_communication"))
        assertTrue(!r.detail.contains("camcorder"))
        assertEquals(-18.5, r.numbers.getValue("voice_communication"), 0.0)
        assertEquals(-120.0, r.numbers.getValue("mic"), 0.0)
        assertEquals(-70.0, r.numbers.getValue("camcorder"), 0.0)
        assertEquals(9L, r.atEpochMs)
    }

    @Test
    fun `all sources silent fails, reports the loudest level and lists the device inputs`() {
        val r = ProbeReducer.micMatrix(true, listOf(MicReading("mic", silent), MicReading("default", -88.0)), listOf("OttoAibox-P3 Pro type=15", "USB-Audio type=11"), 9)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("صامتة"))
        assertTrue(r.detail.contains("-88"))
        assertTrue(r.detail.contains("USB-Audio type=11"))
        assertEquals(r, ProbeCodec.decode(ProbeCodec.encode(r)))
    }

    @Test
    fun `no reading at all fails as unable to open the microphone`() {
        val r = ProbeReducer.micMatrix(true, emptyList(), emptyList(), 9)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("ما قدر"))
    }

    @Test
    fun `without permission the matrix fails and says so`() {
        val r = ProbeReducer.micMatrix(false, emptyList(), emptyList(), 9)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("الصلاحية"))
    }

    @Test
    fun `a level exactly at the threshold still counts as silent`() {
        assertEquals(Status.FAIL, ProbeReducer.micMatrix(true, listOf(MicReading("mic", -50.0)), emptyList(), 9).status)
        assertEquals(Status.PASS, ProbeReducer.micMatrix(true, listOf(MicReading("mic", -49.9)), emptyList(), 9).status)
    }

    @Test
    fun `the simple recording reports its level even when silent`() {
        val r = ProbeReducer.mic(true, ShortArray(16000), 5)
        assertEquals(Status.FAIL, r.status)
        assertEquals(-120.0, r.numbers.getValue("peak_dbfs"), 0.0)
    }

    @Test
    fun `an assistant launch can carry what the microphone heard at that moment`() {
        val heard = ProbeReducer.assist("voice_interaction_session(flags=5)", previous = null, nowMs = 7, micPeakDbfs = -20.0)
        assertEquals(-20.0, heard.numbers.getValue("launch_mic_peak_dbfs"), 0.0)
        assertTrue(heard.detail.contains("يلقط"))
        val deaf = ProbeReducer.assist("android.intent.action.ASSIST", previous = null, nowMs = 7, micPeakDbfs = silent)
        assertEquals(-120.0, deaf.numbers.getValue("launch_mic_peak_dbfs"), 0.0)
        assertTrue(deaf.detail.contains("صامت"))
        assertEquals(Status.PASS, deaf.status)
    }

    @Test
    fun `adding the microphone reading to a launch does not count the launch twice`() {
        val first = ProbeReducer.assist("a", previous = null, nowMs = 7)
        val withMic = ProbeReducer.assist("a", previous = null, nowMs = 7, micPeakDbfs = -20.0)
        assertEquals(first.numbers.getValue("launch_count"), withMic.numbers.getValue("launch_count"), 0.0)
    }
}
