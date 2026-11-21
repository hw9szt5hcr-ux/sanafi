# Sanafi Phase 1 — Steering-Button Assistant Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A long press of the steering wheel button on the Ottocast P3 Pro pauses the music and opens a HUMAIN voice call with Sanafi, who chats in Najdi, runs the five commands on the box, remembers the driver between trips, and gives the music back when the call ends.

**Architecture:** A new Android app (`app.sanafi`) is launched by the voice-search intent the steering button raises. A foreground service takes audio focus, asks our Vercel function for a HUMAIN session grant (sending the memory and the installed-app names), joins the LiveKit room, and answers the agent's `client_tool_call` RPCs by firing Android intents. Every decision that can be made without Android (tool protocol, Arabic name matching, memory, usage, when to end the call) lives in a pure JVM module and is tested there.

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.1, minSdk 29, targetSdk 33, LiveKit Android 2.30.0, kotlinx-serialization, JUnit 4, Robolectric 4.14.1; TypeScript ESM on Vercel with vitest; GitHub Actions builds the APK.

**Spec:** the design doc "وثيقة تصميم: مساعد السيارة الصوتي على Ottocast P3 Pro" — https://claude.ai/artifact/FPwL6TjPUg3Y3yPmgwwXuL (sections "الأوامر في النسخة الأولى", "الذاكرة بين المشاوير", "نتائج المرحلة صفر", "قرار المرحلة الأولى"). Device facts: `docs/superpowers/plans/2026-11-21-sanafi-phase0-build-notes.md`.

## Global Constraints

- Trigger is the steering wheel button only. No wake word, no always-on microphone in this phase.
- The button reaches an app as `android.speech.action.WEB_SEARCH` (measured on the box). The app also answers `android.intent.action.VOICE_COMMAND`, `android.intent.action.ASSIST`, `android.intent.action.VOICE_ASSIST`.
- A second button press while a call is live ends the call.
- Music is paused during a call with `AUDIOFOCUS_GAIN_TRANSIENT` and resumes when focus is abandoned. Never `MAY_DUCK`.
- Call audio plays as media: `AudioType.MediaAudioType()` (the mode that passed the echo test on the box).
- A command must not feel late (one of the owner's failure conditions): commands that only show something (`navigate`, `open_app`) open at the moment the tool is called; commands that need the microphone or the music channel (`play_music`, `call_contact`, the transport keys of `media_control`) run the moment the call has closed, which is at most `TOOL_END_CAP_MS` (4 s) after the tool was called.
- An app may only open another app while one of its own screens is visible. So `CallActivity` stays on screen until every deferred action has run, and the service is started and the call screen opened from the visible activity that received the button press.
- A call never starts without the microphone permission; the owner sees why.
- The app ends calls itself: the owner's HUMAIN plan returns `limits` = null/null (no idle timeout, no maximum duration). Every started minute is billed (765 credits, about 0.5 SAR).
- Tool names are exactly: `play_music`, `navigate`, `call_contact`, `open_app`, `media_control`, `remember`, `forget`. Web search is HUMAIN's built-in `agent.web_search: true`; ending is HUMAIN's built-in `end_call`. At most 16 client tools per session.
- Tool results are JSON of at most 15 KiB; a tool must answer within 120 s.
- The HUMAIN API key lives only in Vercel env. The device secret is never committed. Nothing secret is compiled into the APK.
- Application id `app.sanafi`, app label «سنافي». It is a separate app from the probe (`app.sanafi.probe`), signed with the same `android/keystore/debug.keystore`.
- All UI text is Gulf Arabic, right-to-left. Code, identifiers and comments are English.
- Memory holds at most 3,000 characters, one short fact per line, for the main driver only. The agent can read it aloud, so nothing secret goes in.
- The server keeps answering the probe app unchanged (requests without `mode`, and `mode: "echo"`).
- Every task ends with a commit whose message ends with the two attribution lines used in this repository. Pushing to `main` builds the APK; push only at the three device milestones (after Tasks 8, 9 and 10).
- Each real call during the owner's device runs costs HUMAIN credit; the runsheets state the expected number of calls.

## Review Focus

1. **A tool call with missing, empty or wrong-typed arguments** (`{}`, `{"query": 5}`, invalid JSON): the agent gets `{"ok":false,"error":…}` and the call continues; nothing throws. Pinned in Task 3.
2. **Arabic names that differ only in spelling** (أحمد/احمد, فاطمة/فاطمه, ى/ي, tashkeel) and **queries that match several contacts or apps**: one match acts, several are handed back to the agent to ask, none says so. Pinned in Task 2 and Task 9.
3. **The network drops mid-call, or the room closes without a `session_end` message**: the call ends, audio focus is abandoned so the music resumes, the foreground service stops. Pinned in Task 5 (decision) and Task 8 (cleanup).
4. **The button is pressed twice quickly, or again while connecting**: exactly one call exists; the second press ends it; two rooms are never open. Pinned in Task 8.
5. **The target app is missing or cannot be resolved** (YouTube Music not installed, no dialer, no contacts permission): the tool fails with a sentence the agent can say; no intent is fired that could crash. Pinned in Task 9.

## File Structure

```
server/
  lib/assistant.ts          the assistant session: prompt, intro, tools, variables, limits
  lib/session.ts            (modify) routes mode "assistant"; voice id passthrough and stale-id retry
  lib/humain.ts             (modify) HumainError carries the failing field paths
  api/agent-session.ts      (unchanged entry)
  api/ping.ts               GET, keeps the function warm
  test/assistant.test.ts, test/ping.test.ts
android/
  core/                     (modify) HttpUrlPoster moves here from the probe app; nothing else changes
  assistant-core/           new JVM module, package app.sanafi.assistant.core
    ArabicText.kt, NameMatcher.kt      Arabic normalisation and name matching
    Tools.kt                           ToolOutcome, ToolHandler, ToolRouter, ToolArgs
    Memory.kt, UsageLedger.kt
    CallController.kt                  when a call must end
    DiagLog.kt                         the report the owner copies back
    AssistantSessionClient.kt          talks to our backend
  assistant/                new Android app, package app.sanafi.assistant
    SanafiApp.kt
    data/Prefs.kt, data/Files.kt
    call/CallGate.kt, call/Ports.kt, call/CallUi.kt, call/Earcons.kt
    call/AssistantCall.kt, call/LiveKitRoom.kt, call/CallService.kt
    tools/Intents.kt, tools/AppCatalog.kt, tools/Contacts.kt, tools/DeviceTools.kt
    ui/TriggerActivity.kt, ui/CallActivity.kt, ui/MainActivity.kt
    boot/BootReceiver.kt, boot/ReadyService.kt          (Task 10)
docs/sanafi-runsheet-ar.md  the owner's numbered steps for the three device runs
.github/workflows/android.yml (modify) tests the new modules, publishes sanafi.apk next to sanafi-probe.apk
```

---

### Task 1: Server — the assistant session

**Files:**
- Create: `server/lib/assistant.ts`, `server/api/ping.ts`, `server/test/assistant.test.ts`, `server/test/ping.test.ts`
- Modify: `server/lib/session.ts`, `server/lib/humain.ts`

**Interfaces:**
- Consumes: `handle(req, deps)`, `Deps`, `SessionResponse`, `HumainError` (existing).
- Produces (HTTP, used by Task 6):
  - `POST /api/agent-session` body `{ device_id: string, mode: "assistant", voice_label?: "mul_abdullah" | "mul_abdulaziz", voice_id?: string, memory?: string, apps?: string[], talk_first?: boolean }`.
  - `200` body `{ server_url, participant_token, session_id, expires_at, limits, voice_id }` where `voice_id` is HUMAIN's `effective.voice_id` (absent when HUMAIN returns none).
  - Errors unchanged: `401 {error:"bad_secret"}`, `400 {error:"bad_request"}`, `502 {error:"humain", code}`.
  - `GET /api/ping` → `200 {"ok":true}`, `Cache-Control: no-store`; any other method `405`.
- Produces (TypeScript): `ASSISTANT_PROMPT: string`, `ASSISTANT_INTRO = 'آمر'`, `CLIENT_TOOLS: ClientTool[]`, `MAX_MEMORY_CHARS = 3000`, `MAX_APPS = 80`, `MAX_APP_NAME = 40`, `buildAssistantRequest(input: { deviceId: string; voiceId?: string; memory?: string; apps?: string[]; talkFirst?: boolean }): AssistantSessionRequest`.

- [ ] **Step 1: Write the failing tests** in `server/test/assistant.test.ts`

```ts
it('builds a session with the seven tools, web search and both variables', () => {
  const r = buildAssistantRequest({ deviceId: 'd1', voiceId: 'v1', memory: 'اسمه عبدالله', apps: ['YouTube Music', 'Maps'] });
  expect(r.agent.client_tools.map((t) => t.name)).toEqual(['play_music', 'navigate', 'call_contact', 'open_app', 'media_control', 'remember', 'forget']);
  expect(r.agent.web_search).toBe(true);
  expect(r.agent.intro).toBe('آمر');
  expect(r.agent.voice_id).toBe('v1');
  expect(r.participant).toEqual({ identity: 'd1', gender: 'male' });
  expect(r.variables).toEqual({ memory: 'اسمه عبدالله', apps: 'YouTube Music، Maps' });
});
it('uses a placeholder word when memory or apps are empty, so no variable is ever missing', () => {
  const r = buildAssistantRequest({ deviceId: 'd1' });
  expect(r.variables).toEqual({ memory: 'لا شيء', apps: 'لا شيء' });
  expect(r.agent.voice_id).toBeUndefined();
});
it('braces are removed from memory and app names so a value can never look like a placeholder', () => {
  const r = buildAssistantRequest({ deviceId: 'd1', memory: 'يحب {{x}}', apps: ['A}}B'] });
  expect(r.variables).toEqual({ memory: 'يحب x', apps: 'AB' });
});
it('talk-first sessions have an empty intro', () => {
  expect(buildAssistantRequest({ deviceId: 'd1', talkFirst: true }).agent.intro).toBe('');
});
it('the prompt uses exactly the two variables and names every tool and end_call', () => {
  expect([...ASSISTANT_PROMPT.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()).toEqual(['apps', 'memory']);
  for (const name of [...CLIENT_TOOLS.map((t) => t.name), 'end_call']) expect(ASSISTANT_PROMPT).toContain(name);
  expect(ASSISTANT_PROMPT.length).toBeLessThanOrEqual(4000);
});
it('every tool has a description and a JSON-schema object with its required argument', () => {
  const required = { play_music: ['query'], navigate: ['place'], call_contact: ['name'], open_app: ['app'], media_control: ['action'], remember: ['fact'], forget: ['fact'] };
  for (const t of CLIENT_TOOLS) {
    expect(t.description.length).toBeGreaterThan(20);
    expect(t.parameters).toMatchObject({ type: 'object', required: required[t.name as keyof typeof required] });
  }
  expect(CLIENT_TOOLS.find((t) => t.name === 'media_control')!.parameters.properties.action.enum)
    .toEqual(['play', 'pause', 'next', 'previous', 'volume_up', 'volume_down']);
});
```

and, through `handle()` with fake `deps` (same style as `server/test/handler.test.ts`):

```ts
it('assistant mode sends the assistant request and returns the effective voice id', async () => { /* createSession resolves { ..., effective: { voice_id: 'v9' } } → reply.body.voice_id === 'v9'; the request passed to createSession has agent.client_tools.length === 7 */ });
it('a device-supplied voice id skips the voice lookup', async () => { /* body.voice_id = 'v1' → resolveVoice not called; request.agent.voice_id === 'v1' */ });
it('a stale device voice id is resolved again by label and retried once', async () => { /* first createSession rejects new HumainError('VALIDATION_INVALID_PARAM', 400, ['agent.voice_id']); resolveVoice returns 'v2' → second call uses 'v2'; reply 200 */ });
it('rejects oversized memory, too many apps, a long app name and a non-string app', async () => { /* 3001 chars → 400; 81 apps → 400; a 41-char name → 400; apps: [5] → 400 */ });
it('requests without mode and with mode echo still build the probe session', async () => { /* agent.client_tools undefined, prompt === PROMPT / ECHO_PROMPT */ });
```

`server/test/ping.test.ts`: `GET` → 200 `{ok:true}`; `POST` → 405.

- [ ] **Step 2: Run** `cd server && npm test` — Expected: FAIL, `buildAssistantRequest` is not exported.

- [ ] **Step 3: Implement**
  - `lib/assistant.ts`: the constants and `buildAssistantRequest`. `variables.apps` joins names with `'، '`; `{` and `}` are removed from the memory and from every app name. `agent.tool_acknowledgments = { initial: { enabled: true, after_ms: 1500 }, long_wait: { enabled: true, after_ms: 10000 } }`.
  - `ASSISTANT_PROMPT` (Arabic, at most 4,000 characters) must say, in this order: who Sanafi is (a Saudi driving companion, Najdi dialect, funny, short answers of one or two sentences, speaks to one male driver); what he knows about the driver, as `{{memory}}`; the apps on the box, as `{{apps}}`; when to call each of the seven tools, one line each (for `open_app`, pass a name from the list; for `call_contact`, pass the name as said, and when the tool returns `candidates` ask which one); that after `play_music`, `navigate`, `call_contact`, `open_app` and `media_control` succeed he says one short word such as «أبشر» and nothing more, because the app closes the call; to save with `remember` only lasting facts about the driver and never anything secret; to end with `end_call` when the driver says goodbye or has nothing more.
  - `lib/humain.ts`: `HumainError(code, status?, paths: string[] = [])`; fill `paths` from `data.errors[].path` of the error body.
  - `lib/session.ts`: `parseBody` accepts the new fields with the limits above (`voice_id`: string of 1–64 characters; each app name trimmed and non-empty; `talk_first` a boolean); `SessionResponse` gains `effective?: { voice_id?: string }`; `Deps.createSession` takes `SessionRequest | AssistantSessionRequest` (`makeCreateSession` already serialises whatever it is given); `handle` routes `mode === 'assistant'` to `buildAssistantRequest`, skips `resolveVoice` when `voice_id` is given, and on `HumainError` with code `VALIDATION_INVALID_PARAM` whose `paths` include `agent.voice_id` resolves by label and retries once.
  - `api/ping.ts`.

- [ ] **Step 4: Run** `cd server && npm test && npm run typecheck` — Expected: all tests pass (47 existing + the new ones), `tsc` prints nothing.

- [ ] **Step 5: Deploy and check live** (no HUMAIN minute is billed: the session is never joined). Deploy `server/` to Vercel project `sanafi-backend` (`prj_RTku1oE2ozPzj3VXMTHtzQW5qZ7I`, team `team_pmnJLNrR7W6g2Cs2FPTep9Ia`) with the Vercel MCP, the same way Phase 0 did. Then, with the device secret from `/tmp/device_secret.txt` (ask the owner if the file is gone; never print it into the repo):

```bash
curl -s https://sanafi-backend.vercel.app/api/ping
# Expected: {"ok":true}
curl -s -X POST https://sanafi-backend.vercel.app/api/agent-session -H "x-device-secret: $SECRET" -H 'Content-Type: application/json' \
  -d '{"device_id":"claude-check","mode":"assistant","voice_label":"mul_abdullah","memory":"<3000 Arabic characters>","apps":["YouTube Music","Maps"]}'
# Expected: 200 with server_url, participant_token, session_id and voice_id.
# A 502 with code PAYLOAD_TOO_LARGE means the 3,000-character memory cap is too high: halve MAX_MEMORY_CHARS here and Memory.MAX_CHARS in Task 4, and ledger the ruling.
curl -s -X POST https://sanafi-backend.vercel.app/api/agent-session -H "x-device-secret: $SECRET" -H 'Content-Type: application/json' -d '{"device_id":"claude-check"}'
# Expected: 200 (the probe contract still works).
```

- [ ] **Step 6: Commit** — `feat(server): assistant session with client tools, memory and app variables`

---

### Task 2: Arabic text and name matching (creates `:assistant-core`)

**Files:**
- Create: `android/assistant-core/build.gradle.kts`, `android/assistant-core/src/main/kotlin/app/sanafi/assistant/core/ArabicText.kt`, `.../NameMatcher.kt`, `android/assistant-core/src/test/kotlin/app/sanafi/assistant/core/NameMatcherTest.kt`
- Modify: `android/settings.gradle.kts` (include `:assistant-core`); move `android/app/src/main/java/app/sanafi/probe/net/HttpUrlPoster.kt` to `android/core/src/main/kotlin/app/sanafi/probe/net/HttpUrlPoster.kt` (same package, so the probe's imports do not change).

**Interfaces:**
- Produces:
  - `object ArabicText { fun normalize(text: String): String }` — trims, lowercases, removes tashkeel (U+064B–U+0652, U+0670) and tatweel (U+0640), maps أ إ آ ٱ → ا, ى → ي, ة → ه, ؤ → و, ئ → ي, and collapses whitespace.
  - `sealed interface Match { data class One(val index: Int) : Match; data class Several(val indices: List<Int>) : Match; data object None : Match }`
  - `object NameMatcher { fun match(query: String, candidates: List<String>): Match }` — on normalised text, the first rule that matches anything wins: equal; every query word is the start of some word of the candidate; candidate contains the query. One hit → `One`; more → `Several` with at most 5 indices in candidate order; a blank query → `None`.
- `assistant-core/build.gradle.kts`: copy `android/core/build.gradle.kts` (Kotlin JVM, Java 17), with `api(project(":core"))`, `implementation(libs.serialization.json)`, `implementation(libs.coroutines.core)`, `testImplementation(libs.junit)`, `testImplementation(libs.coroutines.test)`. No serialization compiler plugin: only the JSON tree API (`JsonObject`, `buildJsonObject`) is used, as in `:core`. No ONNX Runtime.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `spelling variants of the same name are equal after normalising`() {
    assertEquals(ArabicText.normalize("أَحْمَد"), ArabicText.normalize("احمد"))
    assertEquals(ArabicText.normalize("فاطمة"), ArabicText.normalize("فاطمه"))
    assertEquals(ArabicText.normalize("  YouTube   MUSIC "), "youtube music")
    assertEquals(ArabicText.normalize("مصطفى"), ArabicText.normalize("مصطفي"))
}
@Test fun `an exact name wins over longer names that contain it`() {
    assertEquals(Match.One(1), NameMatcher.match("احمد", listOf("أحمد العتيبي", "أحمد", "محمد أحمد")))
}
@Test fun `a first name shared by two contacts returns both, in order`() {
    assertEquals(Match.Several(listOf(0, 2)), NameMatcher.match("خالد", listOf("خالد المري", "سعد", "خالد الدوسري")))
}
@Test fun `word starts match so a short app name finds its full label`() {
    assertEquals(Match.One(0), NameMatcher.match("youtube", listOf("YouTube Music", "Maps")))
    assertEquals(Match.One(1), NameMatcher.match("خرائط", listOf("YouTube", "خرائط Google")))
}
@Test fun `a word start beats a name that merely contains the query`() {
    assertEquals(Match.One(1), NameMatcher.match("تس", listOf("واتساب", "تسجيلات")))
}
@Test fun `containment is used when no word starts with the query`() {
    assertEquals(Match.One(0), NameMatcher.match("ساب", listOf("واتساب", "خرائط")))
}
@Test fun `nothing similar and a blank query are no match`() {
    assertEquals(Match.None, NameMatcher.match("تيك توك", listOf("YouTube", "Maps")))
    assertEquals(Match.None, NameMatcher.match("  ", listOf("YouTube")))
}
@Test fun `more than five hits are cut to the first five`() {
    assertEquals(Match.Several(listOf(0, 1, 2, 3, 4)), NameMatcher.match("محمد", List(8) { "محمد $it" }))
}
```

- [ ] **Step 2: Run** `gradle -p android :assistant-core:test` — Expected: FAIL to compile, `ArabicText` unresolved.
- [ ] **Step 3: Implement** `ArabicText` and `NameMatcher` as specified above.
- [ ] **Step 4: Run** `gradle -p android :assistant-core:test :core:test :app:testDebugUnitTest` — Expected: PASS (the last two prove the `HttpUrlPoster` move broke nothing: 87 core, 19 app).
- [ ] **Step 5: Commit** — `feat(assistant-core): Arabic normalisation and name matching`

---

### Task 3: The tool protocol

**Files:** Create `android/assistant-core/src/main/kotlin/app/sanafi/assistant/core/Tools.kt`, `.../test/.../ToolRouterTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class After { KEEP_TALKING, END_CALL }
sealed interface ToolOutcome {
    /** [say] is the sentence suggested to the agent; [data] is extra JSON for it; [thenAct] runs once, after the call has ended.
     *  A handler that can act at once (opening a screen) does so itself before returning and leaves [thenAct] null. */
    data class Done(val say: String? = null, val data: JsonObject? = null, val after: After = After.KEEP_TALKING, val thenAct: (() -> Unit)? = null) : ToolOutcome
    data class Failed(val reason: String) : ToolOutcome
}
fun interface ToolHandler { suspend fun run(args: JsonObject): ToolOutcome }
data class ToolResult(val tool: String, val replyJson: String, val outcome: ToolOutcome, val tookMs: Long)
class ToolRouter(private val handlers: Map<String, ToolHandler>, private val nowMs: () -> Long) {
    /** Never throws. [payload] is the RPC payload `{"tool": "...", "arguments": {...}}`. */
    suspend fun handle(payload: String): ToolResult
}
object ToolArgs { fun string(args: JsonObject, name: String): String? }   // a non-blank JSON string, trimmed; anything else is null
```

- Reply JSON (HUMAIN's contract): `Done(say)` → `{"response": say}` merged with `data`'s fields; `Done` with only `data` → `data`; `Done()` → `{"ok":true}`; `Failed(reason)` → `{"ok":false,"error":reason}`. A reply longer than 15,000 bytes of UTF-8 is replaced by `{"ok":false,"error":"النتيجة أطول من المسموح"}`.

- [ ] **Step 1: Write the failing tests**

```kotlin
private fun router(vararg h: Pair<String, ToolHandler>) = ToolRouter(mapOf(*h)) { 0L }
@Test fun `a handled tool gets its arguments and the reply carries the suggested sentence`() = runTest {
    var seen: JsonObject? = null
    val r = router("play_music" to ToolHandler { seen = it; ToolOutcome.Done(say = "أبشر", after = After.END_CALL) })
        .handle("""{"tool":"play_music","arguments":{"query":"محمد عبده"}}""")
    assertEquals("محمد عبده", ToolArgs.string(seen!!, "query"))
    assertEquals("""{"response":"أبشر"}""", r.replyJson)
    assertEquals("play_music", r.tool)
}
@Test fun `data and sentence are merged, data alone is sent as is, nothing at all is ok true`() = runTest { /* {"response":"أي واحد؟","candidates":["أ","ب"]} ; {"candidates":[…]} ; {"ok":true} */ }
@Test fun `an unknown tool, invalid JSON and a missing tool name all fail without throwing`() = runTest {
    for (payload in listOf("""{"tool":"fly","arguments":{}}""", "not json", """{"arguments":{}}""", "[]")) {
        val r = router().handle(payload)
        assertTrue(r.outcome is ToolOutcome.Failed)
        assertTrue(r.replyJson.startsWith("""{"ok":false,"error":"""))
    }
}
@Test fun `missing arguments object is treated as empty and a handler that throws becomes a failure`() = runTest { /* {"tool":"x"} → handler receives JsonObject(emptyMap()); handler throwing IllegalStateException("boom") → Failed, reply ok:false */ }
@Test fun `wrong-typed and blank arguments read as null`() {
    val args = Json.parseToJsonElement("""{"query":5,"place":"  ","name":" خالد "}""").jsonObject
    assertNull(ToolArgs.string(args, "query")); assertNull(ToolArgs.string(args, "place")); assertNull(ToolArgs.string(args, "missing"))
    assertEquals("خالد", ToolArgs.string(args, "name"))
}
@Test fun `an oversized reply is replaced by a short failure`() = runTest { /* data with a 20,000-character string → replyJson is the fixed failure, under 200 bytes */ }
@Test fun `the time a tool took is measured with the injected clock`() = runTest { /* clock returns 100 then 340 → tookMs == 240 */ }
```

- [ ] **Step 2: Run** `gradle -p android :assistant-core:test` — Expected: FAIL to compile, `ToolRouter` unresolved.
- [ ] **Step 3: Implement** `Tools.kt`.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(assistant-core): client tool protocol`

---

### Task 4: Memory and the usage ledger

**Files:** Create `.../core/Memory.kt`, `.../core/UsageLedger.kt`, `.../test/.../MemoryTest.kt`, `.../test/.../UsageLedgerTest.kt`

**Interfaces:**
- Produces:

```kotlin
class Memory(facts: List<String> = emptyList()) {
    val facts: List<String>
    fun remember(fact: String): Boolean   // false for a blank fact or one already stored (compared with ArabicText.normalize)
    fun forget(query: String): Int        // removes facts whose normalised text contains the normalised query; a blank query removes nothing
    fun clear()
    fun render(): String                  // one fact per line, oldest first; "" when empty
    companion object { const val MAX_CHARS = 3000; const val MAX_FACT_CHARS = 200; fun parse(text: String): Memory }
}
class UsageLedger(minutesByMonth: Map<String, Int> = emptyMap()) {
    fun record(joinedAtMs: Long?, endedAtMs: Long, monthKey: String)   // nothing when the agent never joined; else at least 1 minute
    fun minutes(monthKey: String): Int
    fun encode(): String
    companion object { fun decode(text: String): UsageLedger; fun monthKey(epochMs: Long, zone: java.time.ZoneId): String }   // "2026-11"
}
```

- `remember` cuts a fact to `MAX_FACT_CHARS`, replaces line breaks with spaces, then drops the oldest facts until `render().length <= MAX_CHARS`.
- A recorded call bills `(endedAtMs - joinedAtMs) / 60_000 + 1` minutes (integer division): HUMAIN bills the first minute when the agent joins and a new one every 60 s after that, so its documented 135-second call is 3 minutes.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `facts are kept in order and rendered one per line`() { /* remember "اسمه عبدالله", "قهوته المفضلة سعودية" → render() == "اسمه عبدالله\nقهوته المفضلة سعودية" */ }
@Test fun `a repeated fact in another spelling is not stored twice`() { /* "يحب أغاني محمد عبده" then "يحب اغاني محمد عبده" → second returns false, 1 fact */ }
@Test fun `forget removes every fact that mentions the words and reports how many`() { /* facts "قهوته المفضلة سعودية", "يحب القهوة بدون سكر", "اسمه عبدالله" → forget("قهو") returns 2 and leaves ["اسمه عبدالله"]; forget(" ") returns 0 */ }
@Test fun `the oldest facts are dropped when the memory is full`() { /* 20 facts of 200 chars → render().length <= 3000, the last fact added is present, the first is gone */ }
@Test fun `a long fact is cut and line breaks inside a fact become spaces`() { /* 500-char fact → stored length 200; "a\nb" → "a b" */ }
@Test fun `render and parse round-trip, and blank lines are ignored`() { /* Memory.parse(m.render()).facts == m.facts; parse("\n\nx\n") → ["x"] */ }
// UsageLedger
@Test fun `a call the agent never joined costs nothing`() { /* record(null, 5_000, "2026-11") → minutes == 0 */ }
@Test fun `started minutes are billed whole`() { /* 1 s → 1; 59.9 s → 1; 60 s → 2; 135 s → 3 */ }
@Test fun `months are kept apart and survive encode and decode`() { /* record in "2026-11" and "2026-12" → decode(encode()) gives the same minutes for both; decode("garbage").minutes("2026-11") == 0 */ }
@Test fun `the month key follows the device time zone`() { /* 2026-11-30T22:30:00Z in Asia/Qatar → "2026-12" */ }
```

- [ ] **Step 2: Run** `gradle -p android :assistant-core:test` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** both classes.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(assistant-core): memory and monthly usage ledger`

---

### Task 5: When a call ends, and the report

**Files:** Create `.../core/CallController.kt`, `.../core/DiagLog.kt`, `.../test/.../CallControllerTest.kt`, `.../test/.../DiagLogTest.kt`

**Interfaces:**
- Consumes: `After` (Task 3).
- Produces:

```kotlin
sealed interface CallEvent {
    data class Connected(val atMs: Long) : CallEvent
    data class AgentState(val state: String, val atMs: Long) : CallEvent      // lk.agent.state: initializing | listening | thinking | speaking
    data class LocalSpeaking(val atMs: Long) : CallEvent
    data class ToolFinished(val after: After, val atMs: Long) : CallEvent
    data class SessionEnd(val reason: String) : CallEvent                      // humain.session {type:"session_end"}
    data object Disconnected : CallEvent
    data object UserEnd : CallEvent                                            // second button press or the on-screen button
    data class Tick(val atMs: Long) : CallEvent
}
sealed interface Decision { data object Continue : Decision; data class End(val reason: String) : Decision }
class CallController {
    fun on(event: CallEvent): Decision      // once End is returned, every later event returns the same End
    val joinedAtMs: Long?                   // when the agent's state was first seen; null before
    companion object { const val JOIN_TIMEOUT_MS = 10_000L; const val IDLE_MS = 20_000L; const val MAX_CALL_MS = 600_000L; const val TOOL_END_CAP_MS = 4_000L }
}
class DiagLog(private val capacity: Int = 200) {
    fun add(atMs: Long, tag: String, text: String)
    fun render(header: String): String      // header line, then one line per entry: "<atMs> <tag> <text>", oldest first; line breaks in text become spaces
    fun encode(): String
    companion object { fun decode(text: String, capacity: Int = 200): DiagLog }
}
```

- End reasons, exact strings: `"user"`, `"agent:<reason>"` (for `SessionEnd`), `"disconnected"`, `"agent_missing"` (no agent state within `JOIN_TIMEOUT_MS` of `Connected`), `"idle"` (the agent has been in `listening` and no `LocalSpeaking` arrived for `IDLE_MS`), `"max_duration"` (`MAX_CALL_MS` after `Connected`), `"tool_done"` (after a `ToolFinished(END_CALL)`: when the agent goes `speaking` and then back to `listening`, or `TOOL_END_CAP_MS` after the tool finished, whichever comes first).
- Time-based rules are evaluated on `Tick` and on any event that carries `atMs`.

- [ ] **Step 1: Write the failing tests** — each scripts a timeline and asserts the first `End`.

```kotlin
@Test fun `the agent ending the session and the user ending it are reported with their reasons`() { /* SessionEnd("agent_ended") → End("agent:agent_ended"); UserEnd → End("user") */ }
@Test fun `a room that closes without a session end message ends the call as disconnected`() { /* Connected(0), AgentState("listening",500), Disconnected → End("disconnected") */ }
@Test fun `an agent that never shows up ends the call after ten seconds`() { /* Connected(0), Tick(9_999) → Continue, Tick(10_000) → End("agent_missing") */ }
@Test fun `twenty seconds of listening with nobody speaking ends the call as idle`() { /* Connected(0), AgentState("speaking",1000), AgentState("listening",3000), Tick(22_999) → Continue, Tick(23_000) → End("idle") */ }
@Test fun `the driver speaking restarts the idle clock, and thinking or speaking never counts as idle`() { /* listening at 3000, LocalSpeaking(20_000), Tick(39_999) → Continue, Tick(40_000) → End("idle"); with AgentState("thinking",5000) and Tick(60_000) → Continue */ }
@Test fun `after a tool that ends the call, the call ends when the agent finishes its sentence`() { /* ToolFinished(END_CALL, 10_000), AgentState("speaking",10_300) → Continue, AgentState("listening",11_200) → End("tool_done") */ }
@Test fun `if the agent says nothing after such a tool the call still ends four seconds later`() { /* ToolFinished(END_CALL,10_000), Tick(13_999) → Continue, Tick(14_000) → End("tool_done") */ }
@Test fun `a tool that keeps talking does not end the call`() { /* ToolFinished(KEEP_TALKING,10_000), AgentState("speaking",10_300), AgentState("listening",11_000) → Continue */ }
@Test fun `ten minutes is the hard limit`() { /* Connected(0), AgentState("speaking",1000) kept alive by LocalSpeaking every 10 s, Tick(600_000) → End("max_duration") */ }
@Test fun `the first reason sticks`() { /* UserEnd then SessionEnd("agent_ended") → both End("user") */ }
@Test fun `joinedAtMs is the first agent state seen`() { /* null before; AgentState("initializing", 700) → 700; later states do not change it */ }
// DiagLog
@Test fun `the report lists entries oldest first under its header and keeps only the newest when full`() { /* capacity 3, add 4 → 3 lines after the header, first dropped */ }
@Test fun `line breaks in an entry cannot break the report and the log survives encode and decode`() { }
```

- [ ] **Step 2: Run** `gradle -p android :assistant-core:test` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** `CallController` and `DiagLog`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(assistant-core): call end rules and diagnostic log`

---

### Task 6: Talking to our backend

**Files:** Create `.../core/AssistantSessionClient.kt`, `.../test/.../AssistantSessionClientTest.kt`

**Interfaces:**
- Consumes: `HttpPoster`, `SessionError` from `:core` (`app.sanafi.probe.net`); the HTTP contract of Task 1.
- Produces:

```kotlin
data class SessionAsk(val deviceId: String, val voiceLabel: String, val voiceId: String?, val memory: String, val apps: List<String>, val talkFirst: Boolean)
data class AssistantGrant(val serverUrl: String, val participantToken: String, val sessionId: String, val voiceId: String?)
class AssistantSessionClient(private val http: HttpPoster) {
    suspend fun start(baseUrl: String, secret: String, ask: SessionAsk): Result<AssistantGrant>
}
```

- Body sent: `{"device_id", "mode":"assistant", "voice_label", "voice_id" (only when not null), "memory", "apps", "talk_first"}` to `<baseUrl without trailing slash>/api/agent-session` with headers `x-device-secret` and `Content-Type: application/json`.
- Failures are `SessionError(code)` with the same codes as the probe's `SessionClient`: `"network"` for `IOException`, `"bad_secret"` for 401, the body's `code` or `"HTTP_<status>"` otherwise, `"HTTP_200_BAD_BODY"` for a 200 without the three required fields.

- [ ] **Step 1: Write the failing tests** — a fake `HttpPoster` records the request.

```kotlin
@Test fun `the request carries the assistant mode, memory, apps and the cached voice id`() = runTest { /* parse the recorded JSON: mode "assistant", memory "اسمه عبدالله", apps ["Maps"], voice_id "v1", talk_first false; URL "https://x.app/api/agent-session" for base "https://x.app/"; header x-device-secret */ }
@Test fun `no voice id key is sent when none is cached`() = runTest { }
@Test fun `a grant is read with its voice id, and without one`() = runTest { /* body with voice_id "v9" → grant.voiceId == "v9"; body without → null */ }
@Test fun `failures map to the shared error codes`() = runTest { /* IOException → "network"; 401 → "bad_secret"; 502 {"error":"humain","code":"CREDITS_EXHAUSTED"} → "CREDITS_EXHAUSTED"; 500 "oops" → "HTTP_500"; 200 {} → "HTTP_200_BAD_BODY" */ }
```

- [ ] **Step 2: Run** `gradle -p android :assistant-core:test` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** the client.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(assistant-core): backend session client`

---

### Task 7: The app shell — settings, permissions, button routing, CI

**Files:**
- Create: `android/assistant/build.gradle.kts`, `android/assistant/src/main/AndroidManifest.xml`, `.../assistant/data/Prefs.kt`, `.../assistant/data/Files.kt`, `.../assistant/call/CallGate.kt`, `.../assistant/ui/MainActivity.kt`, `.../assistant/ui/TriggerActivity.kt`, `android/assistant/src/test/java/app/sanafi/assistant/ShellTest.kt`
- Modify: `android/settings.gradle.kts` (include `:assistant`), `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `Memory`, `UsageLedger`, `DiagLog` (Tasks 4–5).
- Produces:

```kotlin
class Prefs(context: Context) {
    var backendUrl: String; var deviceSecret: String
    val deviceId: String                       // "sanafi-" + 8 hex characters, created once
    var voiceLabel: String                     // default "mul_abdullah"
    fun cachedVoiceId(label: String): String?; fun cacheVoiceId(label: String, id: String?)
    var lastServerUrl: String?                 // the LiveKit URL of the last grant, a hint only
    var mediaAudio: Boolean                    // default true
    var talkFirst: Boolean                     // default false (Task 10)
    fun isConfigured(): Boolean                // https URL and a non-blank secret
}
class MemoryFile(context: Context) { fun load(): Memory; fun save(memory: Memory) }    // filesDir/memory.txt
class UsageFile(context: Context) { fun load(): UsageLedger; fun save(ledger: UsageLedger) }
class DiagFile(context: Context) { fun load(): DiagLog; fun save(log: DiagLog) }
object CallGate {                              // the single place that knows whether a call is live; filled in by Task 8
    @Volatile var live: Boolean
    var start: (Context) -> Unit               // default: no-op. Task 8 sets it: start CallService and open CallActivity
    var end: (Context) -> Unit                 // default: no-op. Task 8 sets it to CallService.end
}
```

- `TriggerActivity` (exported, theme `@android:style/Theme.Translucent.NoTitleBar`, `launchMode="singleInstance"`, `excludeFromRecents`): intent filters, each with category `DEFAULT`, for `android.speech.action.WEB_SEARCH`, `android.intent.action.VOICE_COMMAND`, `android.intent.action.ASSIST`, `android.intent.action.VOICE_ASSIST`. In `onCreate` and `onNewIntent`: when `CallGate.live` call `CallGate.end(this)`, otherwise `CallGate.start(this)`; then `finish()`. When the app is not configured it opens `MainActivity` instead.
- `MainActivity` (launcher, RTL, built in code like the probe's): backend URL and device secret fields with «احفظ» (refuses a URL that is not `https://`); voice choice «عبدالله» / «عبدالعزيز»; «جرّب الحين» (same as the button: `CallGate.start`); «الذاكرة» showing `render()` with «امسح الذاكرة» behind a confirmation dialog; a line «هذا الشهر: N دقيقة، حوالي X ريال» with X = N × 0.5; «رجّع زر الدركسون لـ Gemini» calling `packageManager.clearPackagePreferredActivities(packageName)`; «انسخ التقرير» copying `DiagFile.load().render("SANAFI REPORT <device line>")`; on first open it asks for `RECORD_AUDIO`, `READ_CONTACTS`, `CALL_PHONE`, `POST_NOTIFICATIONS`.
- Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `CALL_PHONE`, `RECEIVE_BOOT_COMPLETED`; `CAMERA` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` removed with `tools:node="remove"` as in the probe. `<queries>`: an intent for `MAIN`/`LAUNCHER`, packages `com.google.android.apps.youtube.music` and `com.google.android.apps.maps`, and an intent for `android.intent.action.DIAL`.
- `assistant/build.gradle.kts`: copy the probe's (`android/app/build.gradle.kts`) with `namespace = "app.sanafi.assistant"`, `applicationId = "app.sanafi"`, `versionName = "1.0.<GITHUB_RUN_NUMBER>"`, dependencies `:assistant-core`, appcompat, lifecycle, coroutines-android, serialization-json, livekit; no ONNX Runtime.
- CI: the Gradle step becomes `gradle -p android :core:test :assistant-core:test :app:testDebugUnitTest :assistant:testDebugUnitTest :app:assembleDebug :assistant:assembleDebug --console=plain`; copy `android/assistant/build/outputs/apk/debug/assistant-debug.apk` to `sanafi.apk`; upload and release both `sanafi.apk` and `sanafi-probe.apk`.

- [ ] **Step 1: Write the failing tests** (`@RunWith(RobolectricTestRunner::class) @Config(sdk = [33])`)

```kotlin
@Test fun `the four button intents resolve to the trigger screen`() { /* for each action: packageManager.queryIntentActivities(Intent(action), 0) contains TriggerActivity */ }
@Test fun `a button press starts a call when none is live and ends it when one is`() {
    Prefs(app).apply { backendUrl = "https://x.app"; deviceSecret = "s" }
    var started = 0; var ended = 0
    CallGate.start = { started++ }; CallGate.end = { ended++ }
    CallGate.live = false; Robolectric.buildActivity(TriggerActivity::class.java, Intent("android.speech.action.WEB_SEARCH")).create()
    CallGate.live = true;  Robolectric.buildActivity(TriggerActivity::class.java, Intent("android.speech.action.WEB_SEARCH")).create()
    assertEquals(1, started); assertEquals(1, ended)
}
@Test fun `an unconfigured app opens the settings screen instead of calling`() { /* nextStartedActivity is MainActivity; started == 0 */ }
@Test fun `settings refuse a link that is not https and save a valid one`() { }
@Test fun `the device id is created once and voice ids are cached per voice`() { /* startsWith("sanafi-"); cacheVoiceId("mul_abdullah","v1") → cachedVoiceId("mul_abdullah") == "v1", cachedVoiceId("mul_abdulaziz") == null */ }
@Test fun `memory, usage and the report survive a restart of the app`() { /* save through one instance, load through a new one */ }
@Test fun `clearing the memory asks first and then empties the file`() { /* click, confirm in the dialog → MemoryFile(app).load().facts.isEmpty() */ }
@Test fun `the month line shows minutes and their cost`() { /* ledger with 12 minutes this month → a TextView contains "12" and "6" */ }
```

- [ ] **Step 2: Run** `gradle -p android :assistant:testDebugUnitTest` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** the module, manifest, `Prefs`, `Files`, `CallGate`, the two activities and the CI change.
- [ ] **Step 4: Run** `gradle -p android :assistant:testDebugUnitTest :assistant:assembleDebug` — Expected: PASS, and `android/assistant/build/outputs/apk/debug/assistant-debug.apk` exists.
- [ ] **Step 5: Commit** — `feat(assistant): app shell, settings and steering button routing`

---

### Task 8: The live call — device milestone 1

**Files:**
- Create: `.../assistant/SanafiApp.kt`, `.../assistant/call/Earcons.kt`, `.../call/CallUi.kt`, `.../call/Ports.kt` (`RoomPort`, `FocusPort`, `EarconPort`, `CallFiles`), `.../call/AssistantCall.kt`, `.../call/LiveKitRoom.kt`, `.../call/CallService.kt`, `.../ui/CallActivity.kt`, `.../tools/DeviceTools.kt` (memory tools only in this task), `android/assistant/src/test/java/app/sanafi/assistant/CallTest.kt`, `docs/sanafi-runsheet-ar.md`
- Modify: `AndroidManifest.xml` (`android:name=".SanafiApp"`, the service with `foregroundServiceType="microphone"`, `CallActivity`).

**Interfaces:**
- Consumes: `ToolRouter`, `ToolHandler`, `ToolOutcome`, `After` (Task 3); `Memory` (Task 4); `CallController`, `CallEvent`, `Decision`, `DiagLog` (Task 5); `AssistantSessionClient`, `SessionAsk`, `AssistantGrant` (Task 6); `Prefs`, `MemoryFile`, `UsageFile`, `DiagFile`, `CallGate` (Task 7); `ErrorHints.forCode` and `HttpUrlPoster` from `:core`.
- Produces:

```kotlin
sealed interface CallUi {
    data object Idle : CallUi; data object Connecting : CallUi; data object Listening : CallUi
    data object Thinking : CallUi; data object Speaking : CallUi; data class Failed(val message: String) : CallUi
}
interface EarconPort { fun start(); fun end(); fun error() }
class ToneEarcons : EarconPort                                  // ToneGenerator on STREAM_MUSIC: one short high beep; one low beep; two low beeps
interface FocusPort { fun pauseMusic(); fun resumeMusic() }     // AUDIOFOCUS_GAIN_TRANSIENT with USAGE_ASSISTANT; abandon
class DeviceTools(context: Context) {
    fun handlers(): Map<String, ToolHandler>                    // Task 8: remember, forget. Task 9 adds the other five.
    fun appLabels(): List<String>                               // Task 8: emptyList(). Task 9: the launcher labels, at most 80.
}
/** Everything Android-specific about the room sits behind this, so the call flow is testable. */
interface RoomPort {
    suspend fun warmUp(url: String)                             // LiveKit Room.prepareConnection(url); first used by Task 10
    /** Registers the "client_tool_call" RPC, connects and enables the microphone. [preconnectAudio] is false until Task 10. */
    suspend fun connect(url: String, token: String, preconnectAudio: Boolean, onTool: suspend (payload: String) -> String)
    fun events(): Flow<CallEvent>                               // AgentState, LocalSpeaking, SessionEnd, Disconnected
    fun close()                                                 // disconnect and release; safe to call twice
}
class CallFiles(val memory: MemoryFile, val usage: UsageFile, val diag: DiagFile)
/** One call from trigger to cleanup. */
class AssistantCall(
    private val prefs: Prefs, private val client: AssistantSessionClient,
    private val toolHandlers: Map<String, ToolHandler>, private val appLabels: () -> List<String>,
    private val room: RoomPort, private val focus: FocusPort, private val earcons: EarconPort,
    private val files: CallFiles, private val micGranted: () -> Boolean, private val nowMs: () -> Long,
) {
    suspend fun run(onUi: (CallUi) -> Unit): String             // returns the end reason, or "error:<code>"
    fun requestEnd()                                            // the second button press
}
class LiveKitRoom(context: Context, mediaAudio: Boolean) : RoomPort     // the real room
class CallService : Service() { companion object { fun start(context: Context); fun end(context: Context); val ui: StateFlow<CallUi> } }
```

- `AssistantCall.run` order: `earcons.start()` and `focus.pauseMusic()` first; `client.start(...)` with `SessionAsk(deviceId, voiceLabel, prefs.cachedVoiceId(voiceLabel), memory.render(), appLabels(), prefs.talkFirst)`; on success cache `grant.voiceId` and `grant.serverUrl`; `room.connect(url, token, preconnectAudio = false, onTool)` where `onTool` runs `ToolRouter(toolHandlers, nowMs).handle(payload)`, keeps the outcome's `thenAct`, feeds `CallEvent.ToolFinished(after, nowMs())` for a `Done` outcome and returns `replyJson`; feed `CallEvent.Connected`, the room's events and a `Tick` every 250 ms into `CallController`; on `Decision.End`: `room.close()`, `focus.resumeMusic()`, run every kept `thenAct` (in order, each inside try/catch), `earcons.end()`, record usage with `controller.joinedAtMs`, append the diagnostics lines, `onUi(CallUi.Idle)`, return the reason.
- Before anything else `run` checks the microphone permission (through `micGranted: () -> Boolean`); without it: `earcons.error()`, `onUi(CallUi.Failed("اسمح لسنافي بالميكروفون من شاشة التطبيق"))`, return `"error:no_microphone"`; the music is never paused.
- A grant failure: `earcons.error()`, `focus.resumeMusic()`, `onUi(CallUi.Failed(ErrorHints.forCode(code)))`, return `"error:<code>"`. The room is never opened.
- `onUi(CallUi.Idle)` is emitted last, after the deferred actions ran, so the call screen is still visible when they open another app. After a failure `run` leaves the state at `Failed`; `CallService` shows it for six seconds, then publishes `Idle` and stops.
- Memory tools: `remember{fact}` → `Done(say = "حفظتها")`, or `Done(say = "أعرفها من قبل")` when it was already stored; `forget{fact}` → `Done(say = "نسيتها")`, or `Done(say = "ما لقيت شي بهالمعنى")` when nothing matched; a blank `fact` → `Failed("ناقصني المعلومة")`. All keep the call going.
- The cleanup (`room.close()`, `focus.resumeMusic()`) runs in `finally`, so it also runs when `connect` throws or the coroutine is cancelled.
- `LiveKitRoom`: `LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())))` when `mediaAudio`; `room.localParticipant.registerRpcMethod("client_tool_call") { data -> onTool(data.payload) }` before `room.connect(url, token)`; `room.localParticipant.setMicrophoneEnabled(true)`; agent state by polling `remoteParticipants.values.firstNotNullOfOrNull { it.attributes["lk.agent.state"] }` every 50 ms (the method that worked in the probe); `LocalSpeaking` from `RoomEvent.ActiveSpeakersChanged` when `speakers` contains the local participant; `SessionEnd` from `RoomEvent.DataReceived` on topic `humain.session` with `type == "session_end"`; `Disconnected` from `RoomEvent.Disconnected`.
- `CallService`: `start` uses `startForegroundService`; `onStartCommand` calls `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)`, and when a call is already running does nothing more (one call at a time); sets `CallGate.live` true for the whole run and false in `finally`; `end` calls `requestEnd()` on the running call; stops itself when `run` returns. It builds the call through `var callFactory: (Context) -> AssistantCall`, which tests replace; the real factory passes `DeviceTools(context).handlers()` and `DeviceTools(context)::appLabels`.
- `SanafiApp : Application` (named in the manifest) sets `CallGate.start = { ctx -> CallService.start(ctx); ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK)) }` and `CallGate.end = CallService::end`. Both run from the visible activity that received the press (`TriggerActivity` or `MainActivity`), which is what lets the service use the microphone and the call screen appear.
- `CallActivity`: full screen, shows «يتصل…» / «سنافي يسمعك» / «يفكر…» / «يتكلم» from `CallService.ui`, a large «إنهاء» button calling `CallService.end`, and the failure message with a «إغلاق» button; finishes itself when the state returns to `Idle`, and six seconds after a failure is shown, so a driver never has to touch the screen.
- Diagnostics lines per call (tags): `trigger`, `grant` (ms from trigger, or the error code), `connected` (ms), `joined` (ms), `first_speech` (ms), one `tool` line per call (`name`, `tookMs`, ok or the failure reason), `acted` (ms from the tool to its `thenAct`), `end` (reason, billed minutes, and `heard=<number of LocalSpeaking events>`: zero means the box gave the app a silent microphone, as it did once in Phase 0 until it was restarted).

- [ ] **Step 1: Write the failing tests** with fake `RoomPort`, `FocusPort` and `HttpPoster` (plain JVM logic under Robolectric for the files):

```kotlin
@Test fun `a normal call pauses the music, connects, and gives the music back when the agent ends it`() = runTest { /* fake room emits AgentState("speaking"), AgentState("listening"), SessionEnd("agent_ended") → run returns "agent:agent_ended"; focus calls == ["pause","resume"]; room.closed; usage minutes this month == 1 */ }
@Test fun `the session request carries the memory, the voice label and the cached voice id, and the new id is cached`() = runTest { }
@Test fun `a refused grant never opens the room, resumes the music and reports the hint`() = runTest { /* poster returns 502 CREDITS_EXHAUSTED → "error:CREDITS_EXHAUSTED"; room.connectCalls == 0; focus == ["pause","resume"]; last ui is Failed(ErrorHints.forCode("CREDITS_EXHAUSTED")) */ }
@Test fun `a connection that throws still closes the room and resumes the music`() = runTest { }
@Test fun `a room that drops without a session end ends the call and resumes the music`() = runTest { /* events: AgentState("listening"), Disconnected → "disconnected" */ }
@Test fun `a second button press ends a live call`() = runTest { /* requestEnd() while collecting → "user" */ }
@Test fun `a press while the call is still connecting cancels it and resumes the music`() = runTest { /* the fake poster is still suspended; requestEnd() → run returns "user"; room.connectCalls == 0; focus == ["pause","resume"] */ }
@Test fun `without the microphone permission nothing starts and the reason is shown`() = runTest { /* micGranted = { false } → "error:no_microphone"; focus calls empty; poster never called; last ui is Failed */ }
@Test fun `the screen goes idle only after the deferred actions ran`() = runTest { /* order of recorded steps ends with ["act","ui:Idle"] */ }
@Test fun `the remember tool writes the fact and the agent is told it was saved`() = runTest { /* onTool("""{"tool":"remember","arguments":{"fact":"اسمه عبدالله"}}""") → reply contains "response"; MemoryFile.load().facts == ["اسمه عبدالله"] */ }
@Test fun `forget removes the fact, and a remember call without a fact fails politely`() = runTest { }
@Test fun `an action deferred by a tool runs once, after the room is closed and the music resumed`() = runTest { /* a test handler returns Done(after = END_CALL, thenAct = { order += "act" }) → order == ["close","resume","act"] */ }
@Test fun `a deferred action that throws does not stop the cleanup or the next action`() = runTest { }
@Test fun `starting the service twice runs one call`() { /* Robolectric: CallService.start twice → the injected call factory was used once */ }
```

- [ ] **Step 2: Run** `gradle -p android :assistant:testDebugUnitTest` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** the files above. `CallService` builds the real `AssistantCall` through a factory the tests replace.
- [ ] **Step 4: Run** `gradle -p android :core:test :assistant-core:test :app:testDebugUnitTest :assistant:testDebugUnitTest :assistant:assembleDebug` — Expected: PASS.
- [ ] **Step 5: Write section «التجربة الأولى» of `docs/sanafi-runsheet-ar.md`** (numbered, Gulf Arabic). It also says: Sanafi's voice plays on the car's speech channel, so set its loudness with the volume knob while he is talking; and if he does not answer anything you say, restart the box once and try again. The steps: uninstall «اختبار سنافي»; install `sanafi.apk` from `https://github.com/hw9szt5hcr-ux/sanafi/releases/latest/download/sanafi.apk`; open «سنافي», allow the four permissions, type the server link and the device secret, «احفظ»; long-press the steering button, choose «سنافي» then «دائماً»; with a song playing press the button: the song stops, a beep, Sanafi says «آمر»; say «تذكّر إن اسمي عبدالله», then «مع السلامة»: the song comes back; press the button, ask «وش اسمي؟»; press the button twice in a row: the call ends; «انسخ التقرير» and paste it. About 5 calls, about 3 SAR.
- [ ] **Step 6: Commit and push** — `feat(assistant): live call with music pause, end rules and memory tools` — then confirm the CI run is green and the release has `sanafi.apk`. **Stop for the owner's device run 1.** The report decides: any `error:` line or a missing `joined`/`tool` line is debugged (systematic-debugging skill) before Task 9.

---

### Task 9: The five commands — device milestone 2

**Files:**
- Create: `.../assistant/tools/Intents.kt`, `.../tools/AppCatalog.kt`, `.../tools/Contacts.kt`, `android/assistant/src/test/java/app/sanafi/assistant/DeviceToolsTest.kt`
- Modify: `.../tools/DeviceTools.kt`

**Interfaces:**
- Consumes: `NameMatcher`, `Match` (Task 2); `ToolHandler`, `ToolOutcome`, `After`, `ToolArgs` (Task 3).
- Produces:

```kotlin
object Intents {
    fun playMusic(query: String): Intent      // action MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, package "com.google.android.apps.youtube.music",
                                              // extras SearchManager.QUERY = query and MediaStore.EXTRA_MEDIA_FOCUS = "vnd.android.cursor.item/*", FLAG_ACTIVITY_NEW_TASK
    fun playMusicFallback(query: String): Intent   // ACTION_VIEW https://music.youtube.com/search?q=<url-encoded query>, same package, NEW_TASK
    fun navigate(place: String): Intent       // ACTION_VIEW "google.navigation:q=<url-encoded place>", package "com.google.android.apps.maps", NEW_TASK
    fun navigateFallback(place: String): Intent    // ACTION_VIEW "geo:0,0?q=<url-encoded place>", NEW_TASK, no package
    fun call(number: String): Intent          // ACTION_CALL "tel:<number>", NEW_TASK
    fun dial(number: String): Intent          // ACTION_DIAL "tel:<number>", NEW_TASK
}
class AppCatalog(context: Context) {
    data class App(val label: String, val launch: Intent)
    fun apps(): List<App>                      // launcher activities except this app, sorted by label, at most 80, labels cut to 40 characters
}
class Contacts(context: Context) {
    data class Entry(val name: String, val number: String)
    fun entries(): List<Entry>                 // ContactsContract.CommonDataKinds.Phone; empty without READ_CONTACTS; one entry per contact (its first number)
    fun hasPermission(): Boolean
}
```

- Tool behaviour. Two kinds of success, both `Done(say = "أبشر", after = After.END_CALL, …)`:
  **opens now** — the handler calls `startActivity(intent)` itself before returning, `thenAct` is null (`navigate`, `open_app`);
  **acts after the call** — `thenAct = { startActivity(intent) }`, because the microphone and the music channel are only free once the call has closed (`play_music`, `call_contact`, transport keys).
  - `play_music{query}` (after the call): main intent if it resolves, else the fallback if it resolves, else `Failed("يوتيوب ميوزك مو مثبت على الجهاز")`.
  - `navigate{place}` (now): main, else fallback, else `Failed("ما في تطبيق خرائط على الجهاز")`.
  - `open_app{app}` (now): `NameMatcher.match` over the catalog labels. `One` → launch; `Several` → `Done(data = {"candidates": [labels]})` with `KEEP_TALKING` (the agent asks which); `None` → `Failed("ما لقيت تطبيق بهالاسم")`.
  - `call_contact{name}` (after the call): a name of 5 or more digits (after removing spaces, `+` and `-`) is dialled as given. Otherwise without contacts permission → `Failed("ما عندي صلاحية جهات الاتصال")`; `One` → `Intents.call` when `CALL_PHONE` is granted, else `Intents.dial`, with `say = "أبشر، أدق على <name>"`; `Several` → `Done(data = {"candidates": [names]})` with `KEEP_TALKING`; `None` → `Failed("ما لقيت <name> في جهات الاتصال")`.
  - `media_control{action}`: `volume_up` / `volume_down` → `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE | ADJUST_LOWER, 0)` at once, `Done(say = "أبشر", after = END_CALL)`; `play`, `pause`, `next`, `previous` → `thenAct` dispatches the matching `KEYCODE_MEDIA_*` down and up through `AudioManager.dispatchMediaKeyEvent`, 400 ms after the call closed; any other value → `Failed("ما فهمت الأمر")`.
  - A missing or blank required argument in any tool → `Failed("ناقصني <argument>")`.
- `DeviceTools.appLabels()` returns the catalog labels.

- [ ] **Step 1: Write the failing tests** (Robolectric; register fake resolvable activities with `shadowOf(packageManager).addResolveInfoForIntent`, contacts with a `RoboCursor` on the phone URI):

```kotlin
@Test fun `the music intent targets YouTube Music with the query as a play-from-search request`() { /* action, package, both extras, NEW_TASK flag */ }
@Test fun `navigation and its fallback encode an Arabic place name`() { /* data.toString() == "google.navigation:q=" + URLEncoder.encode("قهوة الفلانية","UTF-8") */ }
@Test fun `play_music uses the search fallback when play-from-search is not offered, and fails when the app is missing`() = runTest { }
@Test fun `play_music says one word, ends the call, and only opens YouTube Music afterwards`() = runTest { /* outcome is Done(say "أبشر", END_CALL, thenAct != null); nextStartedActivity == null before thenAct(), the play-from-search intent after */ }
@Test fun `navigate opens Maps at the moment the tool is called`() = runTest { /* nextStartedActivity is the navigation intent as soon as run() returns; outcome.thenAct == null; after == END_CALL */ }
@Test fun `open_app launches the one match at once, hands back several, and fails on none`() = runTest { /* labels YouTube, YouTube Music, Maps: "maps" → Maps started now; "youtube" → YouTube started (the equal label wins); "you" → Done(data candidates ["YouTube","YouTube Music"], KEEP_TALKING), nothing started; "تيك توك" → Failed */ }
@Test fun `call_contact finds a contact written in another spelling and calls its number`() = runTest { /* contact "أحمد العتيبي" 0555, query "احمد" → thenAct starts ACTION_CALL tel:0555 */ }
@Test fun `two contacts with the same first name are handed back as candidates`() = runTest { }
@Test fun `a spoken phone number is called directly`() = runTest { /* "055 123 4567" → tel:0551234567 */ }
@Test fun `without the contacts permission the tool says so, and without the call permission it opens the dialer`() = runTest { }
@Test fun `volume changes happen at once and transport keys are sent after the call`() = runTest { /* volume_up raises STREAM_MUSIC now; "pause" → no key before thenAct */ }
@Test fun `a blank argument and an unknown media action fail with a sentence, never an exception`() = runTest { }
@Test fun `the app list sent to the agent has at most 80 labels of at most 40 characters and leaves this app out`() { }
```

- [ ] **Step 2: Run** `gradle -p android :assistant:testDebugUnitTest` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** `Intents`, `AppCatalog`, `Contacts` and the five handlers.
- [ ] **Step 4: Run** the full command of Task 8 Step 4 — Expected: PASS.
- [ ] **Step 5: Write section «التجربة الثانية» of the runsheet**: one press per command, with the exact sentence to say and what must happen — «شغّل أغنية الأماكن لمحمد عبده» (YouTube Music plays it), «ودّني لأقرب قهوة» (Maps starts navigation), «ابحث لي عن أفضل قهاوي الدوحة» (he answers by voice), «اتصل على <a name saved on the box>» (the call goes out), «افتح يوتيوب» (the app opens), «علّ الصوت», «وقّف الأغنية»; then «انسخ التقرير». About 8 calls, about 4 SAR. Note for the owner: contacts must exist in the box's Google account.
- [ ] **Step 6: Commit and push** — `feat(assistant): the five commands` — confirm CI and the release. **Stop for the owner's device run 2.** Each command that fails on the box is fixed from its `tool` and `acted` lines before Task 10; a command the box cannot do at all is ledgered as a ruling and reported to the owner.

---

### Task 10: Speed — device milestone 3

Measured in Phase 0: 4.2–5.0 s from the press to the first word (grant 1.5–2.3 s, room 1.2 s, agent join and first word 1.5 s). The spec's proposed target is 2 s from the press to the agent listening.

**Files:**
- Create: `.../assistant/boot/BootReceiver.kt`, `.../boot/ReadyService.kt`, tests in `android/assistant/src/test/java/app/sanafi/assistant/SpeedTest.kt`
- Modify: `.../call/AssistantCall.kt`, `.../call/LiveKitRoom.kt`, `AndroidManifest.xml`, `MainActivity.kt` (a switch «أتكلم أول ما أسمع الصفارة» bound to `Prefs.talkFirst`); in `:core` (`app.sanafi.probe.net`) add `fun interface HttpGetter { suspend fun get(url: String): Int }`, implemented by `HttpUrlPoster` (status code; throws `IOException` when unreachable).

**Interfaces:**
- Consumes: `RoomPort.warmUp` and the `preconnectAudio` argument of `RoomPort.connect` (declared in Task 8, unused until now).
- Produces: `class ReadyService : Service()` with `companion object { fun start(context: Context); const val PING_EVERY_MS = 240_000L; var getter: HttpGetter }` (tests replace `getter`).

The four levers, each with its test:
1. **Parallel start.** `AssistantCall.run` starts `room.warmUp(prefs.lastServerUrl)` (when a URL is cached) at the same time as the grant request instead of after it.
2. **Cached voice id.** Already sent since Task 8; the server then skips the voice list call. Verified here by the report's `grant` time.
3. **Warm process and warm server.** `BootReceiver` (BOOT_COMPLETED, QUICKBOOT_POWERON) and `MainActivity` start `ReadyService`: a foreground service with a low-importance notification «سنافي جاهز» that keeps the process alive and calls `GET <backend>/api/ping` every `PING_EVERY_MS` while the network is up. It never touches the microphone.
4. **Talk first.** When `Prefs.talkFirst` is on: the session is asked with `talk_first: true` (empty intro) and `connect` is called with `preconnectAudio = true`, for which `LiveKitRoom` wraps the connection in LiveKit's `room.withPreconnectAudio { room.connect(url, token) }` (`io.livekit.android.audio`, present in 2.30.0), which records the microphone from the beep and hands the audio to the agent when it joins. Whether HUMAIN's agent uses that audio is not documented: the device run decides.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the room is warmed up while the grant is still being fetched`() = runTest { /* the fake poster suspends until released; room.warmUpCalls == ["wss://cached"] before the grant returns */ }
@Test fun `no warm-up is tried without a cached address, and a failing warm-up does not fail the call`() = runTest { }
@Test fun `talk-first asks the server for an empty intro and connects with pre-connect audio`() = runTest { /* recorded body talk_first true; room.lastPreconnect == true; off by default */ }
@Test fun `boot starts the ready service, and the ready service pings the backend`() { /* BootReceiver.onReceive → nextStartedService is ReadyService; with a fake poster, one ping to "<backend>/api/ping" after start */ }
@Test fun `the ready service does nothing when the app is not configured`() { }
```

- [ ] **Step 2: Run** `gradle -p android :assistant:testDebugUnitTest` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** the four levers.
- [ ] **Step 4: Run** the full command of Task 8 Step 4 — Expected: PASS.
- [ ] **Step 5: Write section «التجربة الثالثة» of the runsheet**: restart the box, wait a minute; five presses asking «كم الساعة؟», then turn on «أتكلم أول ما أسمع الصفارة» and do five presses speaking right after the beep; «انسخ التقرير». About 10 calls, about 5 SAR.
- [ ] **Step 6: Commit and push** — `feat(assistant): faster start and talk-first mode` — confirm CI and the release. **Stop for the owner's device run 3.**
- [ ] **Step 7: Decide from the report and ledger it.** From the ten calls take the medians of `grant`, `connected`, `joined`, `first_speech`. Talk-first becomes the default only if in at least four of its five calls the agent answered the sentence spoken before it joined (the owner says so) — otherwise the switch stays off and its row in the runsheet says why. Report to the owner the median press-to-joined time next to Phase 0's and say plainly whether the 2-second target was met; if it was not, the remaining gap and what would close it are stated, not hidden.

---

### Task 11: Close the phase

**Files:** Modify the design doc (Claude Docs artifact), `docs/sanafi-runsheet-ar.md`, `docs/superpowers/plans/2026-11-21-sanafi-phase0-build-notes.md` is left as is; create `docs/superpowers/plans/2026-11-22-sanafi-phase1-build-notes.md` from the ledger.

- [ ] **Step 1:** Add a section «نتائج المرحلة الأولى» to the design doc: one table row per requirement of «المطلوب في النسخة الأولى» with what the box did in the three device runs, the measured speeds, and the list of rulings that changed the design.
- [ ] **Step 2:** Final review as the executing-plans skill describes, over `git diff <merge-base>..HEAD`, with the Review Focus list above.
- [ ] **Step 3: Commit** — `docs: phase 1 results and build notes`

---

## What this plan leaves out, on purpose

- The wake word «يا سنافي»: needs a USB microphone in the box and a trained model; its own plan after this phase.
- A recorded voice for "no connection": needs a paid voice generation; the phase uses a tone and on-screen text.
- Pressing buttons inside other apps, more than one driver, guest mode, and packaging for other cars (design doc, phases 2 and 3).
