package app.sanafi.probe.probes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.data.ProbeStore
import kotlin.concurrent.thread

/** Takes one boot-listening reading through [source] and stores it next to earlier readings. */
internal fun recordBootReading(context: Context, source: String, fromBoot: Boolean) {
    val granted = MicRecorder.hasPermission(context)
    val pcm = if (granted) MicRecorder.record(3).pcm else ShortArray(0)
    val store = ProbeStore(context)
    store.save(
        ProbeReducer.bootListen(
            source = source,
            fromBoot = fromBoot,
            permissionGranted = granted,
            pcm = pcm,
            secondsAfterBoot = SystemClock.elapsedRealtime() / 1000.0,
            previous = store.get(ProbeId.BOOT_LISTEN),
            nowMs = System.currentTimeMillis(),
        ),
    )
}

/** Starts the listening service when the box finishes booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startForegroundService(Intent(context, BootListenService::class.java).putExtra(BootListenService.EXTRA_FROM_BOOT, true))
        } catch (_: Exception) {
            // The system refused a background start; the probe simply stays without a boot reading.
        }
    }
}

/** Question 3: can a plain foreground service hear the microphone right after boot? */
class BootListenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        thread(name = "boot-listen") {
            try {
                recordBootReading(applicationContext, "service", fromBoot)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "اختبار الاستماع", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("اختبار سنافي")
            .setContentText("يختبر الميكروفون بعد التشغيل")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val CHANNEL = "boot_listen"
        private const val NOTIFICATION_ID = 31
    }
}
