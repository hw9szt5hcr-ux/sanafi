package app.sanafi.assistant.call

import android.media.AudioManager
import android.media.ToneGenerator

/** One short high beep when a call starts, one low when it ends, two low when it fails. */
class ToneEarcons : EarconPort {
    private val tones: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (_: Exception) {
            null
        }
    }

    override fun start() = play(ToneGenerator.TONE_PROP_BEEP, 150)
    override fun end() = play(ToneGenerator.TONE_PROP_PROMPT, 150)
    override fun error() = play(ToneGenerator.TONE_PROP_NACK, 400)

    private fun play(tone: Int, ms: Int) {
        try {
            tones?.startTone(tone, ms)
        } catch (_: Exception) {
            // A missing beep must never break a call.
        }
    }

    private companion object {
        const val VOLUME = 85
    }
}
