package app.sanafi.assistant.data

import android.content.Context
import java.util.UUID

/** Settings typed in by the owner, and small things the app remembers between calls. Nothing secret is compiled into the app. */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sanafi", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = prefs.getString("backend_url", "") ?: ""
        set(value) = prefs.edit().putString("backend_url", value.trim()).apply()

    var deviceSecret: String
        get() = prefs.getString("device_secret", "") ?: ""
        set(value) = prefs.edit().putString("device_secret", value.trim()).apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: "sanafi-${UUID.randomUUID().toString().take(8)}".also {
            prefs.edit().putString("device_id", it).apply()
        }

    /** HUMAIN voice label: mul_abdullah or mul_abdulaziz. */
    var voiceLabel: String
        get() = prefs.getString("voice_label", DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString("voice_label", value).apply()

    /** The voice id HUMAIN used last time for [label]; sending it back saves the server a lookup. */
    fun cachedVoiceId(label: String): String? = prefs.getString("voice_id_$label", null)

    fun cacheVoiceId(label: String, id: String?) {
        prefs.edit().apply { if (id == null) remove("voice_id_$label") else putString("voice_id_$label", id) }.apply()
    }

    /** The call server address of the last call. Only a hint for warming up the next one. */
    var lastServerUrl: String?
        get() = prefs.getString("last_server_url", null)
        set(value) = prefs.edit().putString("last_server_url", value).apply()

    /** Plays the call as media; this is the mode that passed the echo test on the box. */
    var mediaAudio: Boolean
        get() = prefs.getBoolean("media_audio", true)
        set(value) = prefs.edit().putBoolean("media_audio", value).apply()

    /** The driver starts talking at the beep and Sanafi does not greet first. */
    var talkFirst: Boolean
        get() = prefs.getBoolean("talk_first", false)
        set(value) = prefs.edit().putBoolean("talk_first", value).apply()

    fun isConfigured(): Boolean = backendUrl.startsWith("https://") && deviceSecret.isNotBlank()

    companion object {
        const val DEFAULT_VOICE = "mul_abdullah"
    }
}
