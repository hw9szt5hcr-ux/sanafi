package app.sanafi.probe.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real openWakeWord models (fetched by scripts/fetch-wake-models.sh) on real recordings. */
class WakeWordDetectorTest {
    private fun model(name: String): ByteArray = File("../app/src/main/assets/oww/$name").readBytes()

    private fun detector() = WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("alexa_v0.1.onnx"))

    /** 16 kHz mono 16-bit WAV with a plain 44-byte header, padded with silence on both sides. */
    private fun clip(name: String): ShortArray {
        val bytes = javaClass.getResourceAsStream("/oww/$name")!!.readBytes()
        val pcm = ShortArray((bytes.size - 44) / 2)
        ByteBuffer.wrap(bytes, 44, pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return ShortArray(16_000) + pcm + ShortArray(32_000)
    }

    @Test
    fun `the spoken wake word is detected once`() {
        detector().use { detector ->
            val scores = detector.feed(clip("alexa_test.wav"))
            val counter = WakeCounter()
            scores.forEach { counter.onScore(it) }
            assertTrue("max score was ${scores.max()}", scores.max() > 0.9f)
            assertEquals(1, counter.detections)
        }
    }

    @Test
    fun `a different spoken phrase and plain silence are not detected`() {
        detector().use { detector ->
            assertTrue(detector.feed(clip("hey_mycroft_test.wav")).max() < 0.1f)
            assertTrue(detector.feed(ShortArray(32_000)).max() < 0.1f)
        }
    }

    @Test
    fun `audio fed in small uneven pieces gives the same scores as audio fed at once`() {
        val audio = clip("alexa_test.wav")
        val atOnce = detector().use { it.feed(audio) }
        val inPieces = detector().use { detector ->
            audio.toList().chunked(700).flatMap { detector.feed(it.toShortArray()) }
        }
        assertEquals(audio.size / 1280, atOnce.size)
        assertEquals(atOnce, inPieces)
    }
}

class WakeCounterTest {
    @Test
    fun `one long stretch of high scores counts as one detection`() {
        val counter = WakeCounter()
        val fired = (listOf(0.1f) + List(15) { 0.95f } + List(5) { 0.0f }).map { counter.onScore(it) }
        assertEquals(1, counter.detections)
        assertEquals(listOf(1), fired.indices.filter { fired[it] })
        assertEquals(0.95f, counter.maxScore, 0f)
    }

    @Test
    fun `two calls a few seconds apart count as two`() {
        val counter = WakeCounter()
        (List(10) { 0.9f } + List(40) { 0.0f } + List(10) { 0.8f }).forEach { counter.onScore(it) }
        assertEquals(2, counter.detections)
    }

    @Test
    fun `a score just under the threshold never counts but is remembered as the loudest`() {
        val counter = WakeCounter()
        assertFalse(counter.onScore(0.49f))
        assertEquals(0, counter.detections)
        assertEquals(0.49f, counter.maxScore, 0f)
        assertTrue(counter.onScore(0.5f))
    }
}

class WakeReducerTest {
    private fun calling(detections: Int) = WakeRun(WakeMode.CALLING, detections, maxScore = 0.97, avgMsPerFrame = 6.5, listenedSeconds = 60.0)
    private fun quiet(falseAlarms: Int) = WakeRun(WakeMode.QUIET, falseAlarms, maxScore = 0.2, avgMsPerFrame = 6.0, listenedSeconds = 60.0)

    @Test
    fun `three of five calls heard over music passes`() {
        val r = ProbeReducer.wake(permissionGranted = true, run = calling(3), error = null, previous = null, nowMs = 5)
        assertEquals(ProbeId.WAKE_OVER_MUSIC, r.id)
        assertEquals(Status.PASS, r.status)
        assertEquals(3.0, r.numbers.getValue("detections"), 0.0)
        assertEquals(0.97, r.numbers.getValue("max_score"), 0.0)
        assertEquals(6.5, r.numbers.getValue("avg_cpu_ms_per_frame"), 0.0)
        assertEquals(60.0, r.numbers.getValue("listened_s"), 0.0)
        assertEquals(5L, r.atEpochMs)
        assertEquals(r, ProbeCodec.decode(ProbeCodec.encode(r)))
    }

    @Test
    fun `two of five calls heard fails and says how many were heard`() {
        val r = ProbeReducer.wake(true, calling(2), null, null, 5)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.detail.contains("2"))
    }

    @Test
    fun `a quiet run alone is not a verdict but keeps its false alarms`() {
        val r = ProbeReducer.wake(true, quiet(0), null, null, 5)
        assertEquals(Status.NOT_RUN, r.status)
        assertEquals(0.0, r.numbers.getValue("false_alarms"), 0.0)
        assertEquals(60.0, r.numbers.getValue("quiet_listened_s"), 0.0)
        assertFalse(r.numbers.containsKey("detections"))
    }

    @Test
    fun `false alarms during the quiet run fail a probe whose calls were heard`() {
        val heard = ProbeReducer.wake(true, calling(5), null, null, 5)
        val r = ProbeReducer.wake(true, quiet(2), null, heard, 6)
        assertEquals(Status.FAIL, r.status)
        assertEquals(5.0, r.numbers.getValue("detections"), 0.0)
        assertEquals(2.0, r.numbers.getValue("false_alarms"), 0.0)
        assertTrue(r.detail.contains("بالغلط"))
    }

    @Test
    fun `calls heard and a clean quiet run pass in either order`() {
        val quietFirst = ProbeReducer.wake(true, calling(4), null, ProbeReducer.wake(true, quiet(0), null, null, 5), 6)
        val callsFirst = ProbeReducer.wake(true, quiet(0), null, ProbeReducer.wake(true, calling(4), null, null, 5), 6)
        assertEquals(Status.PASS, quietFirst.status)
        assertEquals(Status.PASS, callsFirst.status)
        assertEquals(quietFirst.numbers, callsFirst.numbers)
    }

    @Test
    fun `no permission and a detector that failed to start both fail with their reason`() {
        assertTrue(ProbeReducer.wake(false, null, null, null, 5).detail.contains("الصلاحية"))
        val broken = ProbeReducer.wake(true, null, "model missing", null, 5)
        assertEquals(Status.FAIL, broken.status)
        assertTrue(broken.detail.contains("model missing"))
    }
}
