package app.sanafi.probe.probes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import app.sanafi.probe.core.AudioLevel
import app.sanafi.probe.core.MicWatchLog
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.data.ProbeStore
import kotlin.concurrent.thread

/**
 * Listens for a whole minute in half-second windows, so we can see whether the box opens the
 * car microphone only at certain moments (for example while the steering wheel button is held),
 * and whether Android itself is muting the recording.
 */
class MicWatcher(context: Context) {
    private val context = context.applicationContext
    private val store = ProbeStore(this.context)

    /** Blocks for [seconds]. Saves what it has every few seconds, so an interrupted watch still reports. */
    @SuppressLint("MissingPermission")
    fun run(seconds: Int = WATCH_SECONDS): ProbeResult {
        val isAssistant = holdsAssistantRole()
        val peaks = mutableListOf<Double>()
        var silenced = 0
        var others = 0
        fun save(granted: Boolean): ProbeResult =
            ProbeReducer.micWatch(granted, MicWatchLog(WINDOW_MS, peaks.toList(), silenced, others, isAssistant), System.currentTimeMillis())
                .also(store::save)

        if (!MicRecorder.hasPermission(context)) return save(granted = false)
        val rate = MicRecorder.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return save(granted = true)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (_: Exception) {
            return save(granted = true)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return save(granted = true)
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val window = ShortArray(rate * WINDOW_MS / 1000)
        val totalWindows = seconds * 1000 / WINDOW_MS
        try {
            recorder.startRecording()
            while (peaks.size < totalWindows) {
                var filled = 0
                while (filled < window.size) {
                    val read = recorder.read(window, filled, window.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) break
                peaks += AudioLevel.peakDbfs(window.copyOf(filled))
                if (recorder.activeRecordingConfiguration?.isClientSilenced == true) silenced++
                if (audio.activeRecordingConfigurations.any { it.clientAudioSessionId != recorder.audioSessionId }) others++
                if (peaks.size % SAVE_EVERY_WINDOWS == 0) save(granted = true)
            }
        } catch (_: Exception) {
            // Keep whatever was heard; the result is judged by its windows.
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
        return save(granted = true)
    }

    private fun holdsAssistantRole(): Boolean = try {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val WATCH_SECONDS = 60
        private const val WINDOW_MS = 500
        private const val SAVE_EVERY_WINDOWS = 10
    }
}

/** Keeps the watch alive while the owner leaves the app to press the steering wheel button. */
class MicWatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        thread(name = "mic-watch") {
            try {
                MicWatcher(applicationContext).run()
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "مراقبة الميكروفون", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("اختبار سنافي")
            .setContentText("يراقب الميكروفون لمدة دقيقة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val CHANNEL = "mic_watch"
        private const val NOTIFICATION_ID = 32
        @Volatile private var running = false
    }
}
