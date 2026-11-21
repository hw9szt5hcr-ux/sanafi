package app.sanafi.assistant.call

import app.sanafi.assistant.core.CallEvent
import app.sanafi.assistant.data.DiagFile
import app.sanafi.assistant.data.MemoryFile
import app.sanafi.assistant.data.UsageFile
import kotlinx.coroutines.flow.Flow

/** Everything Android-specific about the call room sits behind this, so the call flow is testable. */
interface RoomPort {
    /** Opens what can be opened before the call is granted. */
    suspend fun warmUp(url: String)

    /** Registers the "client_tool_call" RPC, connects and enables the microphone. */
    suspend fun connect(url: String, token: String, preconnectAudio: Boolean, onTool: suspend (payload: String) -> String)

    /** Agent state changes, the driver speaking, the session end message and a lost connection. */
    fun events(): Flow<CallEvent>

    /** Disconnects and releases; safe to call more than once. */
    fun close()
}

/** Pauses whatever is playing for the length of the call and gives it back afterwards. */
interface FocusPort {
    fun pauseMusic()
    fun resumeMusic()
}

/** Short tones, so the driver knows what happened without looking. */
interface EarconPort {
    fun start()
    fun end()
    fun error()
}

class CallFiles(val memory: MemoryFile, val usage: UsageFile, val diag: DiagFile)
