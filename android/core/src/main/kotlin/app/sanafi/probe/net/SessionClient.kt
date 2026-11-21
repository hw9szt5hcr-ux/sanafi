package app.sanafi.probe.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/** What the app needs to join one call. Valid for about a minute and for one use. */
data class SessionGrant(val serverUrl: String, val participantToken: String, val sessionId: String)

/** A failed attempt to start a call; [code] is understood by ErrorHints. */
class SessionError(val code: String) : Exception(code)

fun interface HttpPoster {
    /** Returns the HTTP status and body. Throws IOException when the server cannot be reached. */
    suspend fun post(url: String, headers: Map<String, String>, json: String): Pair<Int, String>
}

/** Asks our backend to start a HUMAIN Voice Agent session. Never keeps a grant for later. */
class SessionClient(private val http: HttpPoster) {
    suspend fun start(baseUrl: String, secret: String, deviceId: String, voiceLabel: String?, mode: String?): Result<SessionGrant> {
        val body = buildJsonObject {
            put("device_id", deviceId)
            if (voiceLabel != null) put("voice_label", voiceLabel)
            if (mode != null) put("mode", mode)
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
        return when (status) {
            200 -> parseGrant(text)?.let { Result.success(it) } ?: Result.failure(SessionError("HTTP_200_BAD_BODY"))
            401 -> Result.failure(SessionError("bad_secret"))
            else -> Result.failure(SessionError(field(text, "code") ?: "HTTP_$status"))
        }
    }

    private fun parseGrant(text: String): SessionGrant? {
        val url = field(text, "server_url") ?: return null
        val token = field(text, "participant_token") ?: return null
        val id = field(text, "session_id") ?: return null
        return SessionGrant(url, token, id)
    }

    private fun field(text: String, name: String): String? = try {
        Json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
