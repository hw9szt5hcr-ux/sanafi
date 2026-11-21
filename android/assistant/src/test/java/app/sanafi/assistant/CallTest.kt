package app.sanafi.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.sanafi.assistant.call.AssistantCall
import app.sanafi.assistant.call.CallFiles
import app.sanafi.assistant.call.CallGate
import app.sanafi.assistant.call.CallService
import app.sanafi.assistant.call.CallUi
import app.sanafi.assistant.call.EarconPort
import app.sanafi.assistant.call.FocusPort
import app.sanafi.assistant.call.RoomPort
import app.sanafi.assistant.core.After
import app.sanafi.assistant.core.AssistantSessionClient
import app.sanafi.assistant.core.CallEvent
import app.sanafi.assistant.core.Memory
import app.sanafi.assistant.core.ToolHandler
import app.sanafi.assistant.core.ToolOutcome
import app.sanafi.assistant.core.UsageLedger
import app.sanafi.assistant.data.DiagFile
import app.sanafi.assistant.data.MemoryFile
import app.sanafi.assistant.data.Prefs
import app.sanafi.assistant.data.UsageFile
import app.sanafi.assistant.tools.DeviceTools
import app.sanafi.probe.core.ErrorHints
import app.sanafi.probe.net.HttpPoster
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After as AfterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val steps = mutableListOf<String>()
    private val now = 1_795_300_000_000L
    private val grantBody = """{"server_url":"wss://lk","participant_token":"tok","session_id":"as_1","voice_id":"v9"}"""

    private inner class FakeRoom(private vararg val scripted: CallEvent) : RoomPort {
        var connectCalls = 0
        var failConnect = false
        var onTool: (suspend (String) -> String)? = null
        val warmUps = mutableListOf<String>()
        var lastPreconnect: Boolean? = null
        override suspend fun warmUp(url: String) { warmUps += url }
        override suspend fun connect(url: String, token: String, preconnectAudio: Boolean, onTool: suspend (payload: String) -> String) {
            connectCalls++
            lastPreconnect = preconnectAudio
            this.onTool = onTool
            if (failConnect) error("cannot connect")
        }
        override fun events(): Flow<CallEvent> = flow {
            scripted.forEach { emit(it) }
            awaitCancellation()
        }
        override fun close() { steps += "close" }
    }

    private val focus = object : FocusPort {
        override fun pauseMusic() { steps += "pause" }
        override fun resumeMusic() { steps += "resume" }
    }
    private val earcons = object : EarconPort {
        override fun start() { steps += "beep:start" }
        override fun end() { steps += "beep:end" }
        override fun error() { steps += "beep:error" }
    }

    private class Poster(private val status: Int, private val body: String, val gate: CompletableDeferred<Unit>? = null) : HttpPoster {
        var calls = 0
        var json = ""
        override suspend fun post(url: String, headers: Map<String, String>, json: String): Pair<Int, String> {
            calls++
            this.json = json
            gate?.await()
            return status to body
        }
    }

    private fun prefs() = Prefs(app).apply { backendUrl = "https://x.app"; deviceSecret = "s" }

    private fun TestScope.call(
        room: RoomPort,
        poster: HttpPoster,
        handlers: Map<String, ToolHandler> = DeviceTools(app).handlers(),
        mic: Boolean = true,
    ) = AssistantCall(
        prefs = prefs(), client = AssistantSessionClient(poster), toolHandlers = handlers, appLabels = { listOf("Maps") },
        room = room, focus = focus, earcons = earcons, files = CallFiles(MemoryFile(app), UsageFile(app), DiagFile(app)),
        micGranted = { mic }, nowMs = { now }, background = backgroundScope,
    )

    private fun flow() = steps.filter { it in setOf("pause", "resume", "close", "act", "ui:Idle") }
    private val ui: (CallUi) -> Unit = { steps += "ui:" + (it as? CallUi.Failed)?.let { f -> "Failed:${f.message}" }.orEmpty().ifEmpty { it.javaClass.simpleName } }

    @AfterTest
    fun reset() {
        CallService.callFactory = CallService.defaultFactory
        CallGate.live = false
    }

    @Test
    fun `a normal call pauses the music, connects, and gives the music back when the agent ends it`() = runTest {
        val room = FakeRoom(CallEvent.AgentState("speaking", now), CallEvent.AgentState("listening", now), CallEvent.SessionEnd("agent_ended"))
        val reason = call(room, Poster(200, grantBody)).run(ui)
        assertEquals("agent:agent_ended", reason)
        assertEquals(1, room.connectCalls)
        assertEquals(false, room.lastPreconnect)
        assertEquals(listOf("pause", "close", "resume", "ui:Idle"), flow())
        assertTrue(steps.indexOf("beep:start") < steps.indexOf("pause"))
        assertTrue(steps.containsAll(listOf("ui:Connecting", "ui:Speaking", "ui:Listening", "beep:end")))
        assertEquals(1, UsageFile(app).load().minutes(UsageLedger.monthKey(now, ZoneId.systemDefault())))
        val report = DiagFile(app).load().render("H")
        for (tag in listOf(" trigger ", " grant ", " connected ", " joined ", " first_speech ", " end agent:agent_ended")) assertTrue(tag, report.contains(tag))
    }

    @Test
    fun `the session request carries the memory, the apps, the voice label and the cached voice id, and the new id is cached`() = runTest {
        MemoryFile(app).save(Memory(listOf("اسمه عبدالله")))
        prefs().cacheVoiceId("mul_abdullah", "v1")
        val poster = Poster(200, grantBody)
        call(FakeRoom(CallEvent.SessionEnd("agent_ended")), poster).run(ui)
        val sent = Json.parseToJsonElement(poster.json).jsonObject
        assertEquals("اسمه عبدالله", sent.getValue("memory").jsonPrimitive.content)
        assertEquals("mul_abdullah", sent.getValue("voice_label").jsonPrimitive.content)
        assertEquals("v1", sent.getValue("voice_id").jsonPrimitive.content)
        assertEquals(listOf("Maps"), sent.getValue("apps").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("v9", Prefs(app).cachedVoiceId("mul_abdullah"))
        assertEquals("wss://lk", Prefs(app).lastServerUrl)
    }

    @Test
    fun `a refused grant never opens the room, resumes the music and reports the hint`() = runTest {
        val room = FakeRoom()
        val reason = call(room, Poster(502, """{"error":"humain","code":"CREDITS_EXHAUSTED"}""")).run(ui)
        assertEquals("error:CREDITS_EXHAUSTED", reason)
        assertEquals(0, room.connectCalls)
        assertEquals(listOf("pause", "resume"), flow())
        assertEquals("ui:Failed:" + ErrorHints.forCode("CREDITS_EXHAUSTED"), steps.last { it.startsWith("ui:") })
        assertTrue(steps.contains("beep:error"))
        assertEquals(0, UsageFile(app).load().minutes(UsageLedger.monthKey(now, ZoneId.systemDefault())))
    }

    @Test
    fun `a connection that throws still closes the room and resumes the music`() = runTest {
        val room = FakeRoom().apply { failConnect = true }
        val reason = call(room, Poster(200, grantBody)).run(ui)
        assertEquals("error:connect", reason)
        assertEquals(listOf("pause", "close", "resume"), flow())
        assertTrue(steps.last { it.startsWith("ui:") }.startsWith("ui:Failed:"))
    }

    @Test
    fun `a room that drops without a session end ends the call and resumes the music`() = runTest {
        val reason = call(FakeRoom(CallEvent.AgentState("listening", now), CallEvent.Disconnected), Poster(200, grantBody)).run(ui)
        assertEquals("disconnected", reason)
        assertEquals(listOf("pause", "close", "resume", "ui:Idle"), flow())
    }

    @Test
    fun `a second button press ends a live call`() = runTest {
        val call = call(FakeRoom(CallEvent.AgentState("listening", now)), Poster(200, grantBody))
        val running = async { call.run(ui) }
        runCurrent()
        assertFalse(running.isCompleted)
        call.requestEnd()
        assertEquals("user", running.await())
        assertEquals(listOf("pause", "close", "resume", "ui:Idle"), flow())
    }

    @Test
    fun `a press while the call is still connecting cancels it and resumes the music`() = runTest {
        val room = FakeRoom()
        val poster = Poster(200, grantBody, gate = CompletableDeferred())
        val call = call(room, poster)
        val running = async { call.run(ui) }
        runCurrent()
        assertEquals(1, poster.calls)
        call.requestEnd()
        assertEquals("user", running.await())
        assertEquals(0, room.connectCalls)
        assertEquals(listOf("pause", "resume", "ui:Idle"), flow())
    }

    @Test
    fun `without the microphone permission nothing starts and the reason is shown`() = runTest {
        val poster = Poster(200, grantBody)
        val reason = call(FakeRoom(), poster, mic = false).run(ui)
        assertEquals("error:no_microphone", reason)
        assertEquals(0, poster.calls)
        assertTrue(flow().isEmpty())
        assertTrue(steps.last { it.startsWith("ui:") }.startsWith("ui:Failed:"))
    }

    @Test
    fun `the remember tool writes the fact and the agent is told it was saved`() = runTest {
        val room = FakeRoom()
        val call = call(room, Poster(200, grantBody))
        val running = async { call.run(ui) }
        runCurrent()
        val reply = room.onTool!!("""{"tool":"remember","arguments":{"fact":"اسمه عبدالله"}}""")
        assertEquals("حفظتها", Json.parseToJsonElement(reply).jsonObject.getValue("response").jsonPrimitive.content)
        assertEquals(listOf("اسمه عبدالله"), MemoryFile(app).load().facts)
        val again = room.onTool!!("""{"tool":"remember","arguments":{"fact":"اسمه عبدالله"}}""")
        assertEquals("أعرفها من قبل", Json.parseToJsonElement(again).jsonObject.getValue("response").jsonPrimitive.content)
        call.requestEnd()
        running.await()
        assertTrue(DiagFile(app).load().render("H").contains(" tool remember "))
    }

    @Test
    fun `forget removes the fact, and a tool call without its argument fails politely`() = runTest {
        MemoryFile(app).save(Memory(listOf("قهوته المفضلة سعودية", "اسمه عبدالله")))
        val room = FakeRoom()
        val call = call(room, Poster(200, grantBody))
        val running = async { call.run(ui) }
        runCurrent()
        assertEquals("نسيتها", Json.parseToJsonElement(room.onTool!!("""{"tool":"forget","arguments":{"fact":"قهوته"}}""")).jsonObject.getValue("response").jsonPrimitive.content)
        assertEquals(listOf("اسمه عبدالله"), MemoryFile(app).load().facts)
        assertEquals("ما لقيت شي بهالمعنى", Json.parseToJsonElement(room.onTool!!("""{"tool":"forget","arguments":{"fact":"سيارة"}}""")).jsonObject.getValue("response").jsonPrimitive.content)
        for (payload in listOf("""{"tool":"remember","arguments":{}}""", """{"tool":"forget","arguments":{"fact":"  "}}""")) {
            assertEquals("false", Json.parseToJsonElement(room.onTool!!(payload)).jsonObject.getValue("ok").toString())
        }
        call.requestEnd()
        running.await()
    }

    @Test
    fun `an action deferred by a tool runs once, after the room is closed and the music resumed, and the screen goes idle last`() = runTest {
        val handlers = mapOf("play_music" to ToolHandler { ToolOutcome.Done(say = "أبشر", after = After.END_CALL, thenAct = { steps += "act" }) })
        val room = FakeRoom(CallEvent.AgentState("thinking", now))
        val call = call(room, Poster(200, grantBody), handlers)
        val running = async { call.run(ui) }
        runCurrent()
        room.onTool!!("""{"tool":"play_music","arguments":{"query":"x"}}""")
        call.requestEnd()
        running.await()
        assertEquals(listOf("pause", "close", "resume", "act", "ui:Idle"), flow())
        assertTrue(DiagFile(app).load().render("H").contains(" acted "))
    }

    @Test
    fun `a deferred action that throws does not stop the cleanup or the next action`() = runTest {
        val handlers = mapOf(
            "a" to ToolHandler { ToolOutcome.Done(thenAct = { error("boom") }) },
            "b" to ToolHandler { ToolOutcome.Done(thenAct = { steps += "act" }) },
        )
        val room = FakeRoom(CallEvent.AgentState("thinking", now))
        val call = call(room, Poster(200, grantBody), handlers)
        val running = async { call.run(ui) }
        runCurrent()
        room.onTool!!("""{"tool":"a"}""")
        room.onTool!!("""{"tool":"b"}""")
        call.requestEnd()
        assertEquals("user", running.await())
        assertEquals(listOf("pause", "close", "resume", "act", "ui:Idle"), flow())
    }

    @Test
    fun `starting the service twice runs one call, and the gate is live while it runs`() = runTest {
        var built = 0
        val scope = this
        CallService.callFactory = { built++; scope.call(FakeRoom(), Poster(200, grantBody, gate = CompletableDeferred())) }
        val service = Robolectric.buildService(CallService::class.java).create()
        service.startCommand(0, 1)
        service.startCommand(0, 2)
        assertEquals(1, built)
        assertTrue(CallGate.live)
        service.destroy()
    }
}
