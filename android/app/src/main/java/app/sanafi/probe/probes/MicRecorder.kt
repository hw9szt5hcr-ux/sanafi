package app.sanafi.probe.probes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/** Short raw recordings from the device microphone: 16 kHz, mono, 16-bit. */
object MicRecorder {
    const val SAMPLE_RATE = 16_000

    data class Recording(val pcm: ShortArray, val inputDevice: String?)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Blocks for [seconds]. Returns no samples when the recorder cannot be opened. */
    @SuppressLint("MissingPermission")
    fun record(
        seconds: Int,
        source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        preferredDevice: AudioDeviceInfo? = null,
    ): Recording {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return Recording(ShortArray(0), null)
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (_: Exception) {
            return Recording(ShortArray(0), null)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return Recording(ShortArray(0), null)
        }
        val out = ShortArray(SAMPLE_RATE * seconds)
        var filled = 0
        var device: String? = null
        try {
            if (preferredDevice != null) recorder.setPreferredDevice(preferredDevice)
            recorder.startRecording()
            while (filled < out.size) {
                val read = recorder.read(out, filled, minOf(1600, out.size - filled))
                if (read <= 0) break
                filled += read
            }
            device = recorder.routedDevice?.let { "${it.productName} (type ${it.type})" }
        } catch (_: Exception) {
            // Keep whatever was read; the probe judges it by its level.
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
        return Recording(out.copyOf(filled), device)
    }

    fun play(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }
}
