package app.sanafi.probe.probes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.core.WakeCounter
import app.sanafi.probe.core.WakeMode
import app.sanafi.probe.core.WakeRun
import app.sanafi.probe.core.WakeWordDetector
import app.sanafi.probe.data.ProbeStore
import kotlin.concurrent.thread

/**
 * Question 7: can an always-on wake word be heard through the car microphone while music plays?
 * Uses openWakeWord's published "alexa" model as a stand-in for «يا سنافي»: the pipeline is the
 * one in core (WakeWordDetector), the models are fetched at build time into assets/oww.
 */
class WakeListener(context: Context) {
    private val context = context.applicationContext
    private val store = ProbeStore(this.context)

    /** Blocks for [seconds]; [onDetect] is called for each new detection with its score. */
    @SuppressLint("MissingPermission")
    fun run(mode: WakeMode, seconds: Int = LISTEN_SECONDS, onDetect: (Float) -> Unit = {}): ProbeResult {
        val previous = store.get(ProbeId.WAKE_OVER_MUSIC)
        fun save(granted: Boolean, run: WakeRun?, error: String?): ProbeResult =
            ProbeReducer.wake(granted, run, error, previous, System.currentTimeMillis()).also(store::save)

        if (!MicRecorder.hasPermission(context)) return save(false, null, null)
        val detector = try {
            WakeWordDetector(asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("alexa_v0.1.onnx"))
        } catch (e: Throwable) {
            return save(true, null, "ما انفتحت ملفات النداء (${e.javaClass.simpleName})")
        }
        val rate = WakeWordDetector.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 0) * 4 + rate)
        } catch (_: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            detector.close()
            return save(true, null, "ما انفتح الميكروفون")
        }

        val counter = WakeCounter()
        val frame = ShortArray(WakeWordDetector.FRAME_SAMPLES)
        val totalFrames = seconds * rate / frame.size
        var frames = 0
        var cpuNanos = 0L
        fun runSoFar() = WakeRun(
            mode = mode,
            detections = counter.detections,
            maxScore = counter.maxScore.toDouble(),
            avgMsPerFrame = if (frames == 0) 0.0 else cpuNanos / 1e6 / frames,
            listenedSeconds = frames * frame.size / rate.toDouble(),
        )
        try {
            recorder.startRecording()
            while (frames < totalFrames) {
                var filled = 0
                while (filled < frame.size) {
                    val read = recorder.read(frame, filled, frame.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < frame.size) break
                val started = System.nanoTime()
                val scores = detector.feed(frame)
                cpuNanos += System.nanoTime() - started
                frames++
                var detected = false
                for (score in scores) {
                    if (counter.onScore(score)) {
                        detected = true
                        onDetect(score)
                    }
                }
                if (detected || frames % SAVE_EVERY_FRAMES == 0) save(true, runSoFar(), null)
            }
        } catch (_: Exception) {
            // Keep what was heard so far; the result is judged by its counts.
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            detector.close()
        }
        return save(true, runSoFar(), null)
    }

    private fun asset(name: String): ByteArray = context.assets.open("oww/$name").use { it.readBytes() }

    companion object {
        const val LISTEN_SECONDS = 60
        private const val SAVE_EVERY_FRAMES = 25
    }
}

/** Keeps the wake-word test listening while the owner is in the music app. Beeps on each detection. */
class WakeService : Service() {
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
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { name -> WakeMode.values().firstOrNull { it.name == name } } ?: WakeMode.CALLING
        thread(name = "wake-listen") {
            val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Exception) { null }
            try {
                WakeListener(applicationContext).run(mode) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
            } finally {
                running = false
                tone?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "اختبار النداء", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("اختبار سنافي")
            .setContentText("يسمع النداء لمدة دقيقة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        private const val CHANNEL = "wake_listen"
        private const val NOTIFICATION_ID = 33
        @Volatile private var running = false
    }
}
