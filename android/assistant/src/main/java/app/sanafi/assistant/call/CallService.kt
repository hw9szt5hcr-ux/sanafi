package app.sanafi.assistant.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.sanafi.assistant.core.AssistantSessionClient
import app.sanafi.assistant.data.DiagFile
import app.sanafi.assistant.data.MemoryFile
import app.sanafi.assistant.data.Prefs
import app.sanafi.assistant.data.UsageFile
import app.sanafi.assistant.tools.DeviceTools
import app.sanafi.probe.net.HttpUrlPoster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keeps one call alive while the driver is in another app. One call at a time. */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Without the foreground state the call still runs while the call screen is visible.
        }
        if (current != null) return START_NOT_STICKY
        val call = try {
            callFactory(this)
        } catch (e: Exception) {
            // The call library could not start; say so instead of leaving a silent screen.
            DiagFile(this).let { file -> file.save(file.load().apply { add(System.currentTimeMillis(), "end", "error:setup ${e.javaClass.simpleName}") }) }
            state.value = CallUi.Failed("ما قدر سنافي يجهّز المكالمة. انسخ التقرير وأرسله.")
            scope.launch {
                delay(FAILURE_SHOWN_MS)
                state.value = CallUi.Idle
                if (current == null) stopSelf()
            }
            return START_NOT_STICKY
        }
        current = call
        CallGate.live = true
        scope.launch {
            var failed = false
            try {
                failed = call.run { state.value = it }.startsWith("error:")
            } finally {
                if (current === call) {
                    current = null
                    CallGate.live = false
                }
            }
            if (failed) {
                val shown = state.value
                delay(FAILURE_SHOWN_MS)
                if (state.value === shown) state.value = CallUi.Idle
            } else {
                state.value = CallUi.Idle
            }
            if (current == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        current = null
        CallGate.live = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "مكالمة سنافي", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("سنافي")
            .setContentText("سنافي يسمعك")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val CHANNEL = "call"
        private const val NOTIFICATION_ID = 41
        const val FAILURE_SHOWN_MS = 6_000L

        @Volatile private var current: AssistantCall? = null
        private val state = MutableStateFlow<CallUi>(CallUi.Idle)

        /** What the call screen shows. */
        val ui: StateFlow<CallUi> get() = state

        val defaultFactory: (Context) -> AssistantCall = { context ->
            val app = context.applicationContext
            val prefs = Prefs(app)
            val tools = DeviceTools(app)
            AssistantCall(
                prefs = prefs,
                client = AssistantSessionClient(HttpUrlPoster()),
                toolHandlers = tools.handlers(),
                appLabels = tools::appLabels,
                room = LiveKitRoom(app, prefs.mediaAudio),
                focus = MusicFocus(app),
                earcons = ToneEarcons(),
                files = CallFiles(MemoryFile(app), UsageFile(app), DiagFile(app)),
                micGranted = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                nowMs = System::currentTimeMillis,
            )
        }

        /** Builds the call; tests replace it. */
        var callFactory: (Context) -> AssistantCall = defaultFactory

        /** Must be called from a visible screen, which is what lets the service use the microphone. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CallService::class.java))
        }

        fun end(@Suppress("UNUSED_PARAMETER") context: Context) {
            current?.requestEnd()
        }
    }
}
