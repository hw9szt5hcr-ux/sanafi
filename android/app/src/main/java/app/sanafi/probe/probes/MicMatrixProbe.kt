package app.sanafi.probe.probes

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import app.sanafi.probe.core.AudioLevel
import app.sanafi.probe.core.MicReading
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.data.ProbeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Question 2, the wide version: two seconds of recording through every audio source and every
 * input the device lists, to find out whether any path carries the car microphone to an app.
 */
class MicMatrixProbe(private val context: Context) {
    private val sources = listOf(
        "mic" to MediaRecorder.AudioSource.MIC,
        "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
        "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
        "default" to MediaRecorder.AudioSource.DEFAULT,
    )

    suspend fun runAll(onStatus: suspend (String) -> Unit): ProbeResult {
        val granted = MicRecorder.hasPermission(context)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).take(MAX_DEVICES)
        val readings = mutableListOf<MicReading>()

        if (granted) {
            val total = sources.size + 1 + inputs.size
            var step = 0
            suspend fun attempt(label: String, record: () -> MicRecorder.Recording) {
                step++
                withContext(Dispatchers.Main) { onStatus("تكلّم بدون توقف… ($step من $total) $label") }
                val pcm = try { record().pcm } catch (_: Exception) { ShortArray(0) }
                readings += MicReading(label, AudioLevel.peakDbfs(pcm))
            }
            withContext(Dispatchers.IO) {
                for ((label, source) in sources) attempt(label) { MicRecorder.record(SECONDS, source) }

                // The way a phone-call app records: call audio mode plus the call microphone source.
                val previousMode = audioManager.mode
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    attempt("call_mode") { MicRecorder.record(SECONDS, MediaRecorder.AudioSource.VOICE_COMMUNICATION) }
                } finally {
                    audioManager.mode = previousMode
                }

                for (device in inputs) {
                    attempt("input_type${device.type}_id${device.id}") {
                        MicRecorder.record(SECONDS, MediaRecorder.AudioSource.MIC, device)
                    }
                }
            }
        }

        val result = ProbeReducer.micMatrix(
            permissionGranted = granted,
            readings = readings,
            inputDevices = inputs.map { "${it.productName} type=${it.type}" },
            nowMs = System.currentTimeMillis(),
        )
        ProbeStore(context).save(result)
        return result
    }

    private companion object {
        const val SECONDS = 2
        const val MAX_DEVICES = 6
    }
}
