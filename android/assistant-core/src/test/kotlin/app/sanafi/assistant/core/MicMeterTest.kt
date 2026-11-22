package app.sanafi.assistant.core

import java.nio.ShortBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicMeterTest {
    private fun buffer(vararg samples: Short) = ShortBuffer.wrap(samples)

    @Test
    fun `digital silence reads as the floor and its buffers are still counted`() {
        val meter = MicMeter()
        repeat(3) { meter.add(ShortBuffer.wrap(ShortArray(480))) }
        assertEquals(3L, meter.frames)
        assertEquals(-120.0, meter.peakDbfs, 0.0)
    }

    @Test
    fun `the loudest sample of the whole call sets the peak`() {
        val meter = MicMeter()
        meter.add(buffer(0, 16384, -100))
        meter.add(buffer(200))
        assertEquals(-6.02, meter.peakDbfs, 0.01)
        meter.add(buffer(-32768))
        assertEquals(0.0, meter.peakDbfs, 0.001)
        assertEquals(3L, meter.frames)
    }

    @Test
    fun `reading a buffer leaves it where the call library expects it`() {
        val samples = buffer(5, 6, 7)
        MicMeter().add(samples)
        assertEquals(0, samples.position())
    }

    @Test
    fun `the report line shows what was captured and who else was recording`() {
        val stats = MicStats(published = true, frames = 250, peakDbfs = -23.44, recorderSources = listOf(7, 1999), silenced = 1)
        assertEquals("published=true frames=250 peak=-23.4dBFS recorders=2[7,1999] silenced=1", stats.render())
    }

    @Test
    fun `a microphone that gave only silence, or nothing, while the agent listened is deaf`() {
        fun stats(frames: Long, peak: Double) = MicStats(true, frames, peak, emptyList(), 0)
        assertTrue(stats(2000, -120.0).deaf(listenedMs = 20_000))
        assertTrue(stats(0, -120.0).deaf(listenedMs = 20_000))
        assertFalse("a quiet cabin is not a dead microphone", stats(2000, -62.0).deaf(listenedMs = 20_000))
        assertFalse("too short to judge", stats(0, -120.0).deaf(listenedMs = 1_000))
    }
}
