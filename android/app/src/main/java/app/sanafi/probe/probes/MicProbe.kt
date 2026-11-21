package app.sanafi.probe.probes

import android.content.Context
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.data.ProbeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Question 2: does a sideloaded app receive the driver's voice from the car microphone? */
class MicProbe(private val context: Context) {
    private var last = ShortArray(0)

    suspend fun run(seconds: Int = 5): ProbeResult = withContext(Dispatchers.IO) {
        val granted = MicRecorder.hasPermission(context)
        val recording = if (granted) MicRecorder.record(seconds) else MicRecorder.Recording(ShortArray(0), null)
        last = recording.pcm
        val result = ProbeReducer.mic(granted, recording.pcm, System.currentTimeMillis())
        val withDevice = recording.inputDevice?.let { result.copy(detail = "${result.detail} المصدر: $it") } ?: result
        withDevice.also { ProbeStore(context).save(it) }
    }

    fun lastRecording(): ShortArray = last
}
