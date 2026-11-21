package app.sanafi.probe.data

import android.content.Context
import java.util.UUID

/** Backend address and device secret, typed in by the owner. Nothing secret is compiled into the app. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = prefs.getString("backend_url", "") ?: ""
        set(value) = prefs.edit().putString("backend_url", value.trim()).apply()

    var deviceSecret: String
        get() = prefs.getString("device_secret", "") ?: ""
        set(value) = prefs.edit().putString("device_secret", value.trim()).apply()

    /** Plays the call as media instead of as a phone call; some car boxes only route media audio. */
    var mediaAudio: Boolean
        get() = prefs.getBoolean("media_audio", false)
        set(value) = prefs.edit().putBoolean("media_audio", value).apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: "p3pro-${UUID.randomUUID().toString().take(8)}".also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun isConfigured(): Boolean = backendUrl.startsWith("https://") && deviceSecret.isNotBlank()
}
