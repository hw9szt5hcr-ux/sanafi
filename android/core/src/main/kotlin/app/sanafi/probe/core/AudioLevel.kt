package app.sanafi.probe.core

import kotlin.math.abs
import kotlin.math.log10

/** Level maths for 16-bit PCM. 0 dBFS is full scale; silence is negative infinity. */
object AudioLevel {
    private const val FULL_SCALE = 32768.0

    fun peakDbfs(pcm: ShortArray): Double {
        val peak = pcm.maxOfOrNull { abs(it.toInt()) } ?: 0
        return if (peak == 0) Double.NEGATIVE_INFINITY else 20.0 * log10(peak / FULL_SCALE)
    }

    fun isSilent(pcm: ShortArray, thresholdDbfs: Double = -50.0): Boolean = peakDbfs(pcm) < thresholdDbfs
}
