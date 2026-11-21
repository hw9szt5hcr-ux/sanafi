package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {
    @Test
    fun `no samples and all-zero samples are silent`() {
        assertTrue(AudioLevel.isSilent(ShortArray(16000)))
        assertTrue(AudioLevel.isSilent(ShortArray(0)))
        assertEquals(Double.NEGATIVE_INFINITY, AudioLevel.peakDbfs(ShortArray(0)), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, AudioLevel.peakDbfs(ShortArray(8)), 0.0)
    }

    @Test
    fun `a full-scale sample is zero dBFS`() {
        assertEquals(0.0, AudioLevel.peakDbfs(shortArrayOf(0, Short.MAX_VALUE)), 0.01)
        assertEquals(0.0, AudioLevel.peakDbfs(shortArrayOf(Short.MIN_VALUE)), 0.01)
    }

    @Test
    fun `half scale is about minus six dBFS`() {
        assertEquals(-6.02, AudioLevel.peakDbfs(shortArrayOf(16384, -100)), 0.01)
    }

    @Test
    fun `speech-level samples are not silent`() {
        assertFalse(AudioLevel.isSilent(shortArrayOf(3000, -3000)))
    }

    @Test
    fun `faint hiss below the threshold counts as silent`() {
        assertTrue(AudioLevel.isSilent(shortArrayOf(50, -50)))
    }
}
