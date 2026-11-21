package app.sanafi.assistant.core

import app.sanafi.probe.net.HttpPoster
import app.sanafi.probe.net.SessionError
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the app tells our backend when it asks for a call. */
data class SessionAsk(
    val deviceId: String,
    val voiceLabel: String,
    val voiceId: String?,
    val memory: String,
    val apps: List<String>,
    val talkFirst: Boolean,
)

/** What the app needs to join one call. Valid for about a minute and for one use. */
data class AssistantGrant(val serverUrl: String, val participantToken: String, val sessionId: String, val voiceId: String?)

/** Asks our backend to start a HUMAIN Voice Agent session for the assistant. Never keeps a grant for later. */
class AssistantSessionClient(private val http: HttpPoster) {
    suspend fun start(baseUrl: String, secret: String, ask: SessionAsk): Result<AssistantGrant> {
        val body = buildJsonObject {
            put("device_id", ask.deviceId)
            put("mode", "assistant")
            put("voice_label", ask.voiceLabel)
            if (ask.voiceId != null) put("voice_id", ask.voiceId)
            put("memory", ask.memory)
            put("apps", buildJsonArray { ask.apps.forEach { add(JsonPrimitive(it)) } })
            put("talk_first", ask.talkFirst)
        }.toString()
        val (status, text) = try {
            http.post(
                "${baseUrl.trimEnd('/')}/api/agent-session",
                mapOf("x-device-secret" to secret, "Content-Type" to "application/json"),
                body,
            )
        } catch (_: IOException) {
            return Result.failure(SessionError("network"))
        }
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        fun field(name: String): String? = (json?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return when (status) {
            200 -> {
                val url = field("server_url")
                val token = field("participant_token")
                val id = field("session_id")
                if (url == null || token == null || id == null) Result.failure(SessionError("HTTP_200_BAD_BODY"))
                else Result.success(AssistantGrant(url, token, id, field("voice_id")))
            }
            401 -> Result.failure(SessionError("bad_secret"))
            else -> Result.failure(SessionError(field("code") ?: "HTTP_$status"))
        }
    }
}
