package app.sanafi.probe.probes

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import app.sanafi.probe.core.AgentStateTracker
import app.sanafi.probe.core.ErrorHints
import app.sanafi.probe.core.LatencyTimeline
import app.sanafi.probe.core.LatencyTimeline.Mark
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.core.Status
import app.sanafi.probe.data.ProbeStore
import app.sanafi.probe.data.Settings
import app.sanafi.probe.net.HttpUrlPoster
import app.sanafi.probe.net.SessionClient
import app.sanafi.probe.net.SessionError
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Questions 5 and 7: one real HUMAIN call, about 40 seconds, so it costs one billed minute.
 * LATENCY measures the time from the tap to the agent's first word.
 * ECHO is a call where the owner stays silent; any time the agent starts "thinking" it believed
 * it heard someone, which in a silent car means it heard its own voice from the speakers.
 */
class CallProbe(private val context: Context) {
    enum class Mode(val probe: ProbeId, val wire: String?, val seconds: Int) {
        LATENCY(ProbeId.CALL_LATENCY, null, 40),
        ECHO(ProbeId.ECHO, "echo", 40),
    }

    private val settings = Settings(context)
    private val store = ProbeStore(context)
    private val client = SessionClient(HttpUrlPoster())

    suspend fun run(voiceLabel: String?, mode: Mode, onStatus: (String) -> Unit): ProbeResult {
        val result = attempt(voiceLabel, mode, onStatus)
        store.save(result)
        return result
    }

    private fun fail(mode: Mode, detail: String) = ProbeResult(mode.probe, Status.FAIL, detail, atEpochMs = System.currentTimeMillis())

    private suspend fun attempt(voiceLabel: String?, mode: Mode, onStatus: (String) -> Unit): ProbeResult {
        if (!settings.isConfigured()) return fail(mode, "اكتب رابط الخادم وكلمة السر في الإعدادات أول.")
        if (!MicRecorder.hasPermission(context)) return fail(mode, "ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.")

        val timeline = LatencyTimeline()
        val tracker = AgentStateTracker(timeline)
        timeline.mark(Mark.TRIGGER, SystemClock.elapsedRealtime())

        onStatus("يطلب مكالمة من الخادم…")
        val grant = client.start(settings.backendUrl, settings.deviceSecret, settings.deviceId, voiceLabel, mode.wire)
            .getOrElse { error ->
                val code = (error as? SessionError)?.code ?: "network"
                return if (mode == Mode.LATENCY) ProbeReducer.call(timeline, code, System.currentTimeMillis()) else fail(mode, ErrorHints.forCode(code))
            }
        timeline.mark(Mark.TOKEN_RECEIVED, SystemClock.elapsedRealtime())

        val audioManager = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        audioManager.requestAudioFocus(focus)

        val overrides = if (settings.mediaAudio) {
            LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType()))
        } else {
            LiveKitOverrides()
        }
        val room = LiveKit.create(context.applicationContext, overrides = overrides)
        var endReason: String? = null
        var disconnected = false
        var connectError: String? = null

        try {
            coroutineScope {
                val events: Job = launch {
                    room.events.collect { event ->
                        when (event) {
                            is RoomEvent.DataReceived -> if (event.topic == "humain.session") {
                                sessionEndReason(event.data)?.let { endReason = it }
                            }
                            is RoomEvent.Disconnected -> disconnected = true
                            else -> Unit
                        }
                    }
                }
                try {
                    onStatus("يتصل…")
                    room.connect(grant.serverUrl, grant.participantToken)
                    room.localParticipant.setMicrophoneEnabled(true)
                    timeline.mark(Mark.ROOM_CONNECTED, SystemClock.elapsedRealtime())
                    onStatus(if (mode == Mode.ECHO) "المكالمة شغالة — لا تتكلم" else "المكالمة شغالة — كلّم سنافي")
                    val deadline = SystemClock.elapsedRealtime() + mode.seconds * 1000L
                    while (SystemClock.elapsedRealtime() < deadline && !disconnected) {
                        agentState(room)?.let { tracker.onState(it, SystemClock.elapsedRealtime()) }
                        delay(POLL_MS)
                    }
                } catch (e: Exception) {
                    connectError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                } finally {
                    events.cancel()
                }
            }
        } finally {
            try { room.disconnect() } catch (_: Exception) {}
            try { room.release() } catch (_: Exception) {}
            audioManager.abandonAudioFocusRequest(focus)
        }

        val now = System.currentTimeMillis()
        connectError?.let { if (timeline.totalToFirstSpeechMs() == null) return fail(mode, "تعذر الاتصال بالمكالمة: $it") }
        val extra = listOfNotNull(endReason?.let { "سبب الإنهاء: $it" }, "وضع الصوت: ${if (settings.mediaAudio) "وسائط" else "مكالمة"}")
        val base = when (mode) {
            Mode.LATENCY -> ProbeReducer.call(timeline, null, now).let {
                it.copy(numbers = it.numbers + ("agent_turns" to tracker.agentTurns.toDouble()))
            }
            Mode.ECHO -> ProbeReducer.echo(tracker.thinkingEntries, tracker.agentTurns, now)
        }
        return base.copy(detail = (listOf(base.detail) + extra).joinToString(" "))
    }

    private fun agentState(room: Room): String? =
        room.remoteParticipants.values.firstNotNullOfOrNull { it.attributes["lk.agent.state"] }

    private fun sessionEndReason(data: ByteArray): String? = try {
        val message = Json.parseToJsonElement(String(data, Charsets.UTF_8)).jsonObject
        if (message["type"]?.jsonPrimitive?.content == "session_end") message["reason"]?.jsonPrimitive?.content else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
