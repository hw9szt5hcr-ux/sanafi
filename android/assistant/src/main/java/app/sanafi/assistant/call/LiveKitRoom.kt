package app.sanafi.assistant.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import app.sanafi.assistant.core.CallEvent
import app.sanafi.assistant.core.MicMeter
import app.sanafi.assistant.core.MicStats
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import livekit.org.webrtc.AudioTrackSink

/** The real call room: HUMAIN Voice Agent calls run on LiveKit. */
class LiveKitRoom(context: Context, mediaAudio: Boolean) : RoomPort {
    // Media output is the mode that passed the echo test on the Ottocast box.
    private val room = LiveKit.create(
        context.applicationContext,
        overrides = if (mediaAudio) LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())) else LiveKitOverrides(),
    )

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val meter = MicMeter()
    private val recorderSources = LinkedHashSet<Int>()

    @Volatile
    private var published = false

    @Volatile
    private var silenced = 0

    // The call library hands over each captured buffer: 16-bit samples, low byte first.
    private val micSink = AudioTrackSink { data, bitsPerSample, _, _, _, _ ->
        if (bitsPerSample == 16) meter.add((data.duplicate().rewind() as ByteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
    }

    override fun micStats(): MicStats = MicStats(published, meter.frames, meter.peakDbfs, synchronized(recorderSources) { recorderSources.toList() }, silenced)

    /** Notes who is recording on the box right now, and whether Android is muting any of them. */
    private fun sampleRecorders() {
        try {
            val configs = audio.activeRecordingConfigurations
            synchronized(recorderSources) { configs.forEach { recorderSources += it.clientAudioSource } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) configs.count { it.isClientSilenced }.let { if (it > silenced) silenced = it }
        } catch (_: Exception) {
            // Only for the report.
        }
    }

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
            try {
                val track = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
                published = track != null
                track?.addSink(micSink)
            } catch (_: Exception) {
                // The meter is only for the report; the call goes on without it.
            }
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
        var polls = 0
        while (isActive) {
            if (polls++ % RECORDERS_EVERY == 0) sampleRecorders()
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
        const val RECORDERS_EVERY = 20
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
