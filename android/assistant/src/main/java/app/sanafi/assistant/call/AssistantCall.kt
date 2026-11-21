package app.sanafi.assistant.call

import app.sanafi.assistant.core.AssistantSessionClient
import app.sanafi.assistant.core.CallController
import app.sanafi.assistant.core.CallEvent
import app.sanafi.assistant.core.Decision
import app.sanafi.assistant.core.SessionAsk
import app.sanafi.assistant.core.ToolHandler
import app.sanafi.assistant.core.ToolOutcome
import app.sanafi.assistant.core.ToolRouter
import app.sanafi.assistant.core.UsageLedger
import app.sanafi.assistant.data.Prefs
import app.sanafi.probe.core.ErrorHints
import app.sanafi.probe.net.SessionError
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * One call, from the button press to the music coming back: beep, pause the music, ask our
 * backend for a HUMAIN session, join the room, answer the agent's tool calls, and hang up when
 * [CallController] says so. Whatever happens, the room is closed and the music is given back.
 */
class AssistantCall(
    private val prefs: Prefs,
    private val client: AssistantSessionClient,
    private val toolHandlers: Map<String, ToolHandler>,
    private val appLabels: () -> List<String>,
    private val room: RoomPort,
    private val focus: FocusPort,
    private val earcons: EarconPort,
    private val files: CallFiles,
    private val micGranted: () -> Boolean,
    private val nowMs: () -> Long,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val endRequested = CompletableDeferred<Unit>()
    private val lock = Any()

    /** The second button press, or the button on the call screen. */
    fun requestEnd() {
        endRequested.complete(Unit)
    }

    /** Returns why the call ended, or "error:<code>" when it never became a call. */
    suspend fun run(onUi: (CallUi) -> Unit): String {
        val log = files.diag.load()
        val startedAt = nowMs()
        fun note(tag: String, text: String) = synchronized(lock) { log.add(nowMs(), tag, text) }
        fun sinceStart() = "${nowMs() - startedAt}ms"
        fun fail(code: String, message: String): String {
            earcons.error()
            onUi(CallUi.Failed(message))
            note("end", "error:$code")
            files.diag.save(log)
            return "error:$code"
        }

        note("trigger", "voice=${prefs.voiceLabel} talk_first=${prefs.talkFirst}")
        if (!micGranted()) return fail("no_microphone", "اسمح لسنافي بالميكروفون من شاشة التطبيق")

        earcons.start()
        focus.pauseMusic()
        onUi(CallUi.Connecting)

        val controller = CallController()
        val events = Channel<CallEvent>(Channel.UNLIMITED)
        val afterCall = mutableListOf<Pair<Long, () -> Unit>>()
        val router = ToolRouter(toolHandlers, nowMs)
        val onTool: suspend (String) -> String = { payload ->
            val result = router.handle(payload)
            val outcome = result.outcome
            note("tool", "${result.tool} ${result.tookMs}ms " + if (outcome is ToolOutcome.Failed) "failed: ${outcome.reason}" else "ok")
            if (outcome is ToolOutcome.Done) {
                outcome.thenAct?.let { act -> synchronized(lock) { afterCall += nowMs() to act } }
                events.trySend(CallEvent.ToolFinished(outcome.after, nowMs()))
            }
            result.replyJson
        }

        var roomUsed = false
        var heard = 0
        var reason = "user"
        var failure: Pair<String, String>? = null
        try {
            val voiceLabel = prefs.voiceLabel
            val ask = SessionAsk(prefs.deviceId, voiceLabel, prefs.cachedVoiceId(voiceLabel), files.memory.load().render(), appLabels(), prefs.talkFirst)
            val granted = untilEnd { client.start(prefs.backendUrl, prefs.deviceSecret, ask) }
            val grant = granted?.getOrThrow()?.getOrElse { error ->
                val code = (error as? SessionError)?.code ?: "network"
                failure = code to ErrorHints.forCode(code)
                null
            }
            if (grant != null) {
                note("grant", sinceStart())
                prefs.cacheVoiceId(voiceLabel, grant.voiceId)
                prefs.lastServerUrl = grant.serverUrl
                roomUsed = true
                val connected = untilEnd { room.connect(grant.serverUrl, grant.participantToken, false, onTool) }
                if (connected != null && connected.isFailure) {
                    failure = "connect" to "تعذر الاتصال بالمكالمة. تأكد من النت وجرّب مرة ثانية."
                    note("connect", "failed: ${connected.exceptionOrNull()?.javaClass?.simpleName}")
                } else if (connected != null) {
                    note("connected", sinceStart())
                    controller.on(CallEvent.Connected(nowMs()))
                    var firstSpeech = false
                    reason = coroutineScope {
                        val helpers = listOf(
                            launch { room.events().collect { events.send(it) } },
                            launch { while (true) { delay(TICK_MS); events.send(CallEvent.Tick(nowMs())) } },
                            launch { endRequested.await(); events.send(CallEvent.UserEnd) },
                        )
                        try {
                            var ended: String? = null
                            while (ended == null) {
                                val event = events.receive()
                                val wasJoined = controller.joinedAtMs != null
                                val decision = controller.on(event)
                                if (event is CallEvent.LocalSpeaking) heard++
                                if (event is CallEvent.AgentState) {
                                    if (!wasJoined) note("joined", sinceStart())
                                    if (event.state == "speaking" && !firstSpeech) {
                                        firstSpeech = true
                                        note("first_speech", sinceStart())
                                    }
                                    onUi(
                                        when (event.state) {
                                            "listening" -> CallUi.Listening
                                            "thinking" -> CallUi.Thinking
                                            "speaking" -> CallUi.Speaking
                                            else -> CallUi.Connecting
                                        },
                                    )
                                }
                                if (decision is Decision.End) ended = decision.reason
                            }
                            ended
                        } finally {
                            helpers.forEach { it.cancel() }
                        }
                    }
                }
            }
        } finally {
            if (roomUsed) room.close()
            focus.resumeMusic()
        }

        failure?.let { (code, message) -> return fail(code, message) }

        val actions = synchronized(lock) { afterCall.toList() }
        for ((calledAt, act) in actions) {
            try {
                act()
                note("acted", "${nowMs() - calledAt}ms after the tool")
            } catch (e: Exception) {
                note("acted", "failed: ${e.javaClass.simpleName}")
            }
        }
        earcons.end()
        val joinedAt = controller.joinedAtMs
        val month = UsageLedger.monthKey(nowMs(), ZoneId.systemDefault())
        val ledger = files.usage.load()
        val before = ledger.minutes(month)
        ledger.record(joinedAt, nowMs(), month)
        files.usage.save(ledger)
        note("end", "$reason minutes=${ledger.minutes(month) - before} heard=$heard")
        files.diag.save(log)
        onUi(CallUi.Idle)
        return reason
    }

    /**
     * Runs [block] off to the side and returns its outcome, or null when the driver ended the
     * call first. The side work is not waited for then: a slow network must not keep the music paused.
     */
    private suspend fun <T> untilEnd(block: suspend () -> T): Result<T>? {
        val work = background.async { runCatching { block() } }
        return try {
            select {
                work.onAwait { it }
                endRequested.onAwait { null }
            }
        } finally {
            work.cancel()
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
