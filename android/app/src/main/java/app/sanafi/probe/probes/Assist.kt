package app.sanafi.probe.probes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import app.sanafi.probe.core.AudioLevel
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.data.ProbeStore
import app.sanafi.probe.ui.MainActivity
import kotlin.concurrent.thread

private const val BOOT_WINDOW_MS = 180_000L

/**
 * Saves the launch at once, then listens for three seconds and adds what the microphone heard:
 * some car boxes only hand the car microphone to the app that was opened as the assistant.
 */
private fun recordAssistLaunch(context: Context, action: String) {
    val appContext = context.applicationContext
    val store = ProbeStore(appContext)
    val previous = store.get(ProbeId.ASSIST_BUTTON)
    val now = System.currentTimeMillis()
    store.save(ProbeReducer.assist(action, previous, now))
    if (!MicRecorder.hasPermission(appContext)) return
    thread(name = "assist-listen") {
        try {
            val peak = AudioLevel.peakDbfs(MicRecorder.record(3).pcm)
            store.save(ProbeReducer.assist(action, previous, now, peak))
        } catch (_: Exception) {
            // The launch itself is already saved.
        }
    }
}

/** Question 4, intent path: the steering button or system fires an assist or voice-command intent. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordAssistLaunch(this, intent?.action ?: "unknown")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/**
 * Makes the app selectable as the device's digital assistant. When it is selected, the system
 * starts this service at boot, which gives a second reading for question 3 through the
 * assistant path.
 */
class SanafiInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        val fromBoot = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
        thread(name = "assistant-listen") {
            try {
                recordBootReading(applicationContext, "assistant", fromBoot)
            } catch (_: Exception) {
                // A refused recording leaves the reading absent; the report shows that.
            }
        }
    }
}

class SanafiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SanafiSession(this)
}

/** Question 4, assistant path: the system opens an assistant session (long-press of the assist key). */
class SanafiSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        recordAssistLaunch(context, "voice_interaction_session(flags=$showFlags)")
        try {
            startAssistantActivity(Intent(context, MainActivity::class.java))
        } catch (_: Exception) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }
}

/** Android only lists an assistant that also names a speech recognizer; this one does nothing. */
class SanafiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) = Unit
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
