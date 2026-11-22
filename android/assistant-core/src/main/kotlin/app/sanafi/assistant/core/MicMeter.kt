package app.sanafi.assistant.core

import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/** Measures what the microphone actually handed to the call: how many buffers, and how loud at most. */
class MicMeter {
    @Volatile
    var frames = 0L
        private set

    @Volatile
    private var peak = 0

    /** Reads one buffer of 16-bit samples without moving the caller's position. */
    fun add(pcm: ShortBuffer) {
        val view = pcm.duplicate()
        var loudest = peak
        while (view.hasRemaining()) {
            val level = abs(view.get().toInt())
            if (level > loudest) loudest = level
        }
        peak = loudest
        frames++
    }

    /** 0 is full scale; [FLOOR_DBFS] means every sample was exactly zero. */
    val peakDbfs: Double get() = if (peak == 0) FLOOR_DBFS else (20 * log10(peak / 32768.0)).coerceAtLeast(FLOOR_DBFS)

    companion object {
        const val FLOOR_DBFS = -120.0
    }
}

/**
 * The microphone side of one call, for the report: [recorderSources] are the Android audio
 * sources seen recording on the box during the call, [silenced] how many of those recordings
 * Android was muting at once.
 */
data class MicStats(val published: Boolean, val frames: Long, val peakDbfs: Double, val recorderSources: List<Int>, val silenced: Int) {
    fun render(): String =
        "published=$published frames=$frames peak=${String.format(Locale.US, "%.1f", peakDbfs)}dBFS " +
            "recorders=${recorderSources.size}[${recorderSources.joinToString(",")}] silenced=$silenced"

    /** True when the agent listened for a while and the microphone gave it nothing but silence. */
    fun deaf(listenedMs: Long): Boolean = listenedMs >= MIN_LISTEN_MS && (frames < MIN_FRAMES || peakDbfs <= DEAD_DBFS)

    private companion object {
        const val MIN_LISTEN_MS = 3_000L
        const val MIN_FRAMES = 100L
        const val DEAD_DBFS = -90.0
    }
}
