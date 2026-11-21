package app.sanafi.assistant.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import app.sanafi.assistant.core.CallEvent
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The real call room: HUMAIN Voice Agent calls run on LiveKit. */
class LiveKitRoom(context: Context, mediaAudio: Boolean) : RoomPort {
    // Media output is the mode that passed the echo test on the Ottocast box.
    private val room = LiveKit.create(
        context.applicationContext,
        overrides = if (mediaAudio) LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())) else LiveKitOverrides(),
    )

    override suspend fun warmUp(url: String) {
        try {
            room.prepareConnection(url)
        } catch (_: Exception) {
            // Only a head start; the call connects without it.
        }
    }

    override suspend fun connect(url: String, token: String, preconnectAudio: Boolean, onTool: suspend (payload: String) -> String) {
        // HUMAIN's agent calls client tools through this RPC method on the user's participant.
        // The same thread the probe connected from, which is the path proven on the box.
        withContext(Dispatchers.Main.immediate) {
            room.localParticipant.registerRpcMethod("client_tool_call") { data -> onTool(data.payload) }
            room.connect(url, token)
            room.localParticipant.setMicrophoneEnabled(true)
        }
    }

    override fun events(): Flow<CallEvent> = channelFlow {
        launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.DataReceived -> if (event.topic == "humain.session") sessionEndReason(event.data)?.let { send(CallEvent.SessionEnd(it)) }
                    is RoomEvent.Disconnected -> send(CallEvent.Disconnected)
                    is RoomEvent.ActiveSpeakersChanged ->
                        if (event.speakers.any { it.identity == room.localParticipant.identity }) send(CallEvent.LocalSpeaking(System.currentTimeMillis()))
                    else -> Unit
                }
            }
        }
        // The agent publishes what it is doing as a participant attribute; polling it is what worked on the box.
        var last: String? = null
        while (isActive) {
            val state = room.remoteParticipants.values.firstNotNullOfOrNull { it.attributes["lk.agent.state"] }
            if (state != null && state != last) {
                last = state
                send(CallEvent.AgentState(state, System.currentTimeMillis()))
            }
            delay(POLL_MS)
        }
    }

    override fun close() {
        try { room.disconnect() } catch (_: Exception) {}
        try { room.release() } catch (_: Exception) {}
    }

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

/** Takes the sound for the length of a call, which makes music apps pause, and gives it back after. */
class MusicFocus(context: Context) : FocusPort {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()

    override fun pauseMusic() {
        try { audio.requestAudioFocus(request) } catch (_: Exception) {}
    }

    override fun resumeMusic() {
        try { audio.abandonAudioFocusRequest(request) } catch (_: Exception) {}
    }
}
