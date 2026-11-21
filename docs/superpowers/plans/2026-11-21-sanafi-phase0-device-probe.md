# Sanafi Phase 0 — Device Probe Implementation Plan

> **For agentic workers:** Use the executing-plans skill to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A small Android "probe" app plus a one-function backend that answer, on the owner's real Ottocast P3 Pro, the eight Phase 0 questions the design depends on.

**Architecture:** One Kotlin Android app (`app.sanafi.probe`) with a results screen; each probe writes a `ProbeResult` to a local store and the owner copies one text report back to us. One Vercel function holds the HUMAIN API key and starts Voice Agent sessions; the app joins them with the LiveKit Android SDK. No product features are built here: no memory, no client tools, no Arabic wake model.

**Tech Stack:** Kotlin, Android Gradle Plugin, LiveKit Android SDK, ONNX Runtime Android (openWakeWord models), TypeScript on Vercel Functions, `@humain-voice/sdk` 0.18.1, vitest, GitHub Actions.

**Spec:** https://claude.ai/artifact/FPwL6TjPUg3Y3yPmgwwXuL (design doc "وثيقة تصميم: مساعد السيارة الصوتي على Ottocast P3 Pro"). Phase 0 table = section "المراحل".

## Global Constraints

- Device: Ottocast P3 Pro, Android 13. `minSdk 29`, `targetSdk 33`, `compileSdk 34`.
- Package `app.sanafi.probe`. Code, file names and identifiers in English; every on-screen string in Arabic, layout RTL.
- The HUMAIN API key never ships in the app or the repo. It lives only in the backend env var `API_KEY`. The key needs the `agent` scope, otherwise HUMAIN answers `403 AUTH_SCOPE_MISSING`.
- HUMAIN base URL `https://api.voice.humain.com`. Sessions: `POST /agent/sessions`. Unknown request fields are rejected with `400`.
- The participant token must be used within about 60 seconds (`expires_at`): request it only at trigger time and connect immediately. Never cache a token.
- Billing: 765 credits per started minute. Every probe call costs at least one minute. The app never starts a call without an explicit tap by the owner.
- One user per call. Tool limit 16, tool timeout 120 s (not used in Phase 0, recorded for Phase 1).
- Backend URL and device secret are typed into the app's settings screen and stored in `SharedPreferences`; nothing secret is compiled in.
- Speed target to measure against: 2000 ms from trigger to the agent's first speech. This is a provisional number, not a pass/fail gate.
- Wake phrase chosen by the owner: "يا سنافي". Phase 0 does not train it (see Task 8).
- Commit after every task. No push, deploy, or anything that spends HUMAIN credits without the owner's explicit OK.
- No Gradle wrapper jar in the repo; CI installs Gradle 8.7 with `gradle/actions/setup-gradle`.

## Review Focus

1. Backend unreachable or no internet when a call probe starts: the probe is saved as FAIL with the reason, the app does not crash. Pinned in Task 3 (`LatencyTimeline` with missing marks) and Task 7 (`SessionClient` failure).
2. Microphone route exists but delivers silence (car not feeding audio): MIC probe must be FAIL "silent", never PASS. Pinned in Task 3 (`AudioLevel`).
3. HUMAIN refuses the session (`403 AUTH_SCOPE_MISSING`, `402 CREDITS_EXHAUSTED`, `429 CONCURRENCY_LIMIT_EXCEEDED`, `429 RATE_LIMIT_EXCEEDED`): the backend passes the code through and the app shows a plain Arabic hint per code. Pinned in Task 2 and Task 3 (`ErrorHints`).
4. Wrong or missing device secret: backend answers `401` and starts no session (no cost). Pinned in Task 2.
5. Microphone or phone permission denied by the owner: the probe is saved as FAIL "permission denied" and the screen says how to grant it. Pinned in Task 3 (`ProbeReducer`).

---

## File Structure

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/app/sanafi/probe/
    core/AudioLevel.kt          pure: PCM16 level maths
    core/LatencyTimeline.kt     pure: call timing marks and deltas
    core/ProbeResult.kt         pure: ProbeId, Status, ProbeResult
    core/ProbeReducer.kt        pure: probe outcome from raw observations
    core/ErrorHints.kt          pure: HUMAIN code -> Arabic hint
    core/ReportFormatter.kt     pure: results -> one text report
    data/ProbeStore.kt          SharedPreferences JSON store
    data/Settings.kt            backend URL + device secret
    net/SessionClient.kt        POST to our backend
    probes/MicProbe.kt, BootListenService.kt, BootReceiver.kt,
           AssistActivity.kt, SanafiInteractionService.kt, SanafiSessionService.kt,
           CallProbe.kt, WakeProbe.kt, TelephonyProbe.kt
    ui/MainActivity.kt, SettingsActivity.kt
  app/src/test/java/app/sanafi/probe/core/*Test.kt
server/
  package.json, tsconfig.json, vercel.json
  api/agent-session.ts          HTTP handler
  lib/session.ts                pure request builder + auth + error mapping
  lib/voices.ts                 voice label -> id lookup
  test/session.test.ts, test/handler.test.ts
.github/workflows/android.yml   build + unit tests + APK artifact and release
.github/workflows/server.yml    server tests
docs/device-runsheet-ar.md      numbered Arabic steps for the owner
```

---

### Task 1: Installable shell app built by CI (answers Q1)

**Files:**
- Create: everything under `android/` listed above except `probes/*` and `net/*`; `.github/workflows/android.yml`
- Test: `android/app/src/test/java/app/sanafi/probe/core/ProbeResultTest.kt`

**Interfaces:**
- Produces:
  - `enum class ProbeId { INSTALL, MIC, BOOT_LISTEN, ASSIST_BUTTON, CALL_LATENCY, ECHO, WAKE_OVER_MUSIC, TELEPHONY }`
  - `enum class Status { NOT_RUN, PASS, FAIL }`
  - `data class ProbeResult(val id: ProbeId, val status: Status, val detail: String, val numbers: Map<String, Double> = emptyMap(), val atEpochMs: Long)`
  - `class ProbeStore(context: Context) { fun save(r: ProbeResult); fun all(): List<ProbeResult> }` — `all()` returns one entry per `ProbeId`, `NOT_RUN` when nothing saved.
  - `MainActivity`: a list of the eight probes with status, a "run" button per probe, and "انسخ التقرير".

- [ ] **Step 1: Write the failing test**

```kotlin
class ProbeResultTest {
    @Test fun `ids are the eight phase-0 questions in order`() {
        assertEquals(
            listOf("INSTALL","MIC","BOOT_LISTEN","ASSIST_BUTTON","CALL_LATENCY","ECHO","WAKE_OVER_MUSIC","TELEPHONY"),
            ProbeId.values().map { it.name })
    }
    @Test fun `a fresh result defaults to no numbers`() {
        assertTrue(ProbeResult(ProbeId.MIC, Status.PASS, "ok", atEpochMs = 1L).numbers.isEmpty())
    }
}
```

- [ ] **Step 2: Create the Gradle project and the workflow, run the test in CI terms**

Run locally if a JDK 17 + Android SDK exist: `gradle -p android testDebugUnitTest`. Expected: FAIL, unresolved `ProbeId`. If the sandbox has no Android SDK, say so and rely on CI in Step 5.

- [ ] **Step 3: Implement `core/ProbeResult.kt`, `data/ProbeStore.kt`, `ui/MainActivity.kt`**

`MainActivity` saves `INSTALL = PASS` on first launch (the app running proves the sideload). Store format: one JSON string per `ProbeId.name` key.

- [ ] **Step 4: Write `.github/workflows/android.yml`**

Triggers: push to `main`, `workflow_dispatch`. Steps: checkout, `actions/setup-java@v4` (temurin 17), `gradle/actions/setup-gradle@v4` with `gradle-version: 8.7`, `gradle -p android testDebugUnitTest assembleDebug`, upload `android/app/build/outputs/apk/debug/app-debug.apk` as artifact `sanafi-probe-apk`, and publish it as a release asset named `sanafi-probe.apk` on tag `probe-<run_number>`.

- [ ] **Step 5: Verify**

After the owner approves a push: the workflow run is green and the release has `sanafi-probe.apk`. Expected test output: `ProbeResultTest > 2 tests passed`.

- [ ] **Step 6: Commit** — `git commit -m "feat: probe app shell with results store and CI build"`

---

### Task 2: Backend session endpoint

**Files:**
- Create: `server/package.json`, `server/tsconfig.json`, `server/vercel.json`, `server/api/agent-session.ts`, `server/lib/session.ts`, `server/lib/voices.ts`, `.github/workflows/server.yml`
- Test: `server/test/session.test.ts`, `server/test/handler.test.ts`

**Interfaces:**
- Produces HTTP: `POST /api/agent-session`, header `x-device-secret`, JSON body `{ "device_id": string, "voice_label"?: "mul_abdullah" | "mul_abdulaziz" }`.
  - `200` → `{ "server_url": string, "participant_token": string, "session_id": string, "expires_at": string, "limits": { "max_duration_s": number|null, "idle_timeout_s": number|null } }`
  - `401` → `{ "error": "bad_secret" }` (no HUMAIN call made)
  - `400` → `{ "error": "bad_request" }` when `device_id` is missing, empty, or longer than 128 chars
  - `502` → `{ "error": "humain", "code": string }` where `code` is HUMAIN's error code verbatim
- Produces functions in `lib/session.ts`:
  - `isAuthorized(headerValue: string | undefined, secret: string): boolean` (constant-time compare)
  - `buildSessionRequest(input: { deviceId: string; voiceId?: string }): SessionRequest`
  - `toErrorBody(err: unknown): { status: 502; body: { error: "humain"; code: string } }` (`code` is `"UNKNOWN"` when the error carries none)
  - `handle(req: { secret: string | undefined; body: unknown }, deps: { createSession: (r: SessionRequest) => Promise<SessionResponse>; resolveVoice: (label?: string) => Promise<string | undefined> }): Promise<{ status: number; body: object }>` — reads `DEVICE_SECRET` from env; tests set it to `s3cret`.
- Env: `API_URL`, `API_KEY`, `DEVICE_SECRET`.

- [ ] **Step 1: Write the failing tests**

```ts
test('builds the phase-0 request', () => {
  const r = buildSessionRequest({ deviceId: 'p3pro-1', voiceId: 'v-123' });
  expect(r.participant).toEqual({ identity: 'p3pro-1', gender: 'male' });
  expect(r.agent.voice_id).toBe('v-123');
  expect(r.agent.intro).toBe('هلا والله، آمرني.');
  expect(r.agent.prompt).toContain('سنافي');
  expect(r.agent.web_search).toBe(false);
  expect(Object.keys(r)).toEqual(['participant', 'agent']);
});
test('omits voice_id when no voice was resolved', () => {
  expect('voice_id' in buildSessionRequest({ deviceId: 'd' }).agent).toBe(false);
});
test('secret check', () => {
  expect(isAuthorized('s3cret', 's3cret')).toBe(true);
  expect(isAuthorized('wrong', 's3cret')).toBe(false);
  expect(isAuthorized(undefined, 's3cret')).toBe(false);
});
test('handler: wrong secret is 401 and never calls HUMAIN', async () => {
  const createSession = vi.fn();
  const res = await handle({ secret: 'x', body: { device_id: 'd' } }, { createSession, resolveVoice: async () => undefined });
  expect(res.status).toBe(401);
  expect(createSession).not.toHaveBeenCalled();
});
test.each(['AUTH_SCOPE_MISSING', 'CREDITS_EXHAUSTED', 'CONCURRENCY_LIMIT_EXCEEDED', 'RATE_LIMIT_EXCEEDED'])(
  'handler: HUMAIN code %s is passed through as 502', async (code) => {
    const createSession = vi.fn().mockRejectedValue(Object.assign(new Error('x'), { code }));
    const res = await handle({ secret: 's3cret', body: { device_id: 'd' } }, { createSession, resolveVoice: async () => undefined });
    expect(res).toEqual({ status: 502, body: { error: 'humain', code } });
  });
test('handler: device_id over 128 chars is 400', async () => { /* 129 chars -> status 400, createSession not called */ });
```

- [ ] **Step 2: Run** `cd server && npm test`. Expected: FAIL, `buildSessionRequest` not defined.

- [ ] **Step 3: Implement `lib/session.ts`**

Prompt copy (exact): `أنت "سنافي"، رفيق سواق سعودي. تتكلم باللهجة النجدية، خفيف دم ومختصر. هذي مكالمة اختبار: رد بجملة أو جملتين.`

- [ ] **Step 4: Implement `lib/voices.ts` and `api/agent-session.ts`**

`resolveVoice(label?: string): Promise<string | undefined>` uses `TTSClient.listVoices({ timeoutSeconds: 5 })` from `@humain-voice/sdk`, matches on `label`, caches the label→id map in module scope for the function's lifetime, returns `undefined` on any failure (the session then uses HUMAIN's default voice). The handler wires `AgentClient.createSession` from `@humain-voice/sdk/agent` and returns `session.tokenResponse()` plus `session_id`, `expires_at`, `limits`. `handle(req, deps)` is the testable core; the default export adapts Vercel's request to it.

- [ ] **Step 5: Run** `cd server && npm test`. Expected: all tests PASS.

- [ ] **Step 6: Commit** — `git commit -m "feat: backend session endpoint with secret check and HUMAIN error passthrough"`

---

### Task 3: Probe core logic (pure Kotlin)

**Files:**
- Create: `core/AudioLevel.kt`, `core/LatencyTimeline.kt`, `core/ProbeReducer.kt`, `core/ErrorHints.kt`, `core/ReportFormatter.kt`
- Test: `AudioLevelTest.kt`, `LatencyTimelineTest.kt`, `ProbeReducerTest.kt`, `ErrorHintsTest.kt`, `ReportFormatterTest.kt`

**Interfaces:**
- Consumes: `ProbeId`, `Status`, `ProbeResult` (Task 1).
- Produces:
  - `object AudioLevel { fun peakDbfs(pcm: ShortArray): Double; fun isSilent(pcm: ShortArray, thresholdDbfs: Double = -50.0): Boolean }` — empty or all-zero input gives `Double.NEGATIVE_INFINITY` and `isSilent == true`.
  - `class LatencyTimeline { enum class Mark { TRIGGER, TOKEN_RECEIVED, ROOM_CONNECTED, AGENT_JOINED, AGENT_FIRST_SPEECH }; fun mark(m: Mark, atMs: Long); fun deltas(): Map<String, Long>; fun totalToFirstSpeechMs(): Long? }` — `deltas()` keys are `"TRIGGER->TOKEN_RECEIVED"` etc. for consecutive marks that both exist.
  - `object ProbeReducer { fun mic(permissionGranted: Boolean, pcm: ShortArray, nowMs: Long): ProbeResult; fun call(timeline: LatencyTimeline, errorCode: String?, nowMs: Long): ProbeResult; fun echo(selfInterruptions: Int, agentTurns: Int, nowMs: Long): ProbeResult }`
  - `object ErrorHints { fun forCode(code: String): String }`
  - `object ReportFormatter { fun format(results: List<ProbeResult>, device: String): String }`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun silenceIsSilent() {
    assertTrue(AudioLevel.isSilent(ShortArray(16000)))
    assertTrue(AudioLevel.isSilent(ShortArray(0)))
    assertEquals(Double.NEGATIVE_INFINITY, AudioLevel.peakDbfs(ShortArray(0)), 0.0)
}
@Test fun fullScaleIsZeroDbfs() {
    assertEquals(0.0, AudioLevel.peakDbfs(shortArrayOf(0, Short.MAX_VALUE)), 0.01)
    assertFalse(AudioLevel.isSilent(shortArrayOf(3000, -3000)))
}
@Test fun timelineTotals() {
    val t = LatencyTimeline()
    t.mark(Mark.TRIGGER, 1000); t.mark(Mark.TOKEN_RECEIVED, 1400)
    t.mark(Mark.ROOM_CONNECTED, 1900); t.mark(Mark.AGENT_JOINED, 2300); t.mark(Mark.AGENT_FIRST_SPEECH, 2800)
    assertEquals(1800L, t.totalToFirstSpeechMs())
    assertEquals(400L, t.deltas()["TRIGGER->TOKEN_RECEIVED"])
}
@Test fun timelineWithoutSpeechHasNoTotal() {
    val t = LatencyTimeline(); t.mark(Mark.TRIGGER, 1000)
    assertNull(t.totalToFirstSpeechMs()); assertTrue(t.deltas().isEmpty())
}
@Test fun micDeniedIsFail() {
    val r = ProbeReducer.mic(permissionGranted = false, pcm = ShortArray(0), nowMs = 5)
    assertEquals(Status.FAIL, r.status); assertTrue(r.detail.contains("الصلاحية"))
}
@Test fun micSilentIsFailNotPass() {
    assertEquals(Status.FAIL, ProbeReducer.mic(true, ShortArray(16000), 5).status)
}
@Test fun micWithSignalPassesAndReportsPeak() {
    val r = ProbeReducer.mic(true, shortArrayOf(8000, -8000), 5)
    assertEquals(Status.PASS, r.status); assertNotNull(r.numbers["peak_dbfs"])
}
@Test fun callWithErrorCodeIsFailWithHint() {
    val r = ProbeReducer.call(LatencyTimeline(), "CREDITS_EXHAUSTED", 5)
    assertEquals(Status.FAIL, r.status); assertEquals(ErrorHints.forCode("CREDITS_EXHAUSTED"), r.detail)
}
@Test fun callWithoutFirstSpeechIsFail() {
    val t = LatencyTimeline(); t.mark(Mark.TRIGGER, 0); t.mark(Mark.TOKEN_RECEIVED, 300)
    assertEquals(Status.FAIL, ProbeReducer.call(t, null, 5).status)
}
@Test fun callReportsTotalMs() {
    /* full timeline as in timelineTotals -> PASS, numbers["total_ms"] == 1800.0 */
}
@Test fun everyKnownCodeHasItsOwnHint() {
    val codes = listOf("AUTH_SCOPE_MISSING","CREDITS_EXHAUSTED","CONCURRENCY_LIMIT_EXCEEDED","RATE_LIMIT_EXCEEDED","bad_secret","network")
    assertEquals(codes.size, codes.map { ErrorHints.forCode(it) }.toSet().size)
    assertTrue(ErrorHints.forCode("SOMETHING_NEW").isNotBlank())
}
@Test fun reportListsAllEightProbes() {
    val text = ReportFormatter.format(ProbeId.values().map { ProbeResult(it, Status.NOT_RUN, "", atEpochMs = 0) }, "P3 Pro / Android 13")
    ProbeId.values().forEach { assertTrue(text.contains(it.name)) }
    assertTrue(text.startsWith("SANAFI PROBE REPORT"))
}
```

- [ ] **Step 2: Run** `gradle -p android testDebugUnitTest`. Expected: FAIL, unresolved references.
- [ ] **Step 3: Implement the five files.** `call` PASS means the first speech arrived; the 2000 ms target is reported as a number, not used as the gate. `echo` is FAIL when `selfInterruptions >= 2`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: pure probe logic for audio level, call timing, outcomes and report"`

---

### Task 4: Microphone probe (answers Q2)

**Files:** Create `probes/MicProbe.kt`; modify `AndroidManifest.xml` (`RECORD_AUDIO`), `ui/MainActivity.kt`.

**Interfaces:**
- Consumes: `ProbeReducer.mic`, `ProbeStore`.
- Produces: `class MicProbe(context: Context) { suspend fun run(): ProbeResult; fun lastRecording(): ShortArray }` — records 5 s, 16 kHz mono PCM16 with `MediaRecorder.AudioSource.VOICE_RECOGNITION`; the screen offers "شغّل التسجيل" to play it back.

- [ ] **Step 1:** Implement `MicProbe.run()`: request permission if missing, record, pass the samples to `ProbeReducer.mic`, save.
- [ ] **Step 2: Verify on any Android 13 emulator or phone:** tapping the MIC row while speaking shows PASS with a `peak_dbfs` above -50; denying the permission shows FAIL with the permission hint. On the car device the owner follows the runsheet.
- [ ] **Step 3: Commit** — `git commit -m "feat: microphone probe with playback"`

---

### Task 5: Boot and background listening probe (answers Q3)

**Files:** Create `probes/BootReceiver.kt`, `probes/BootListenService.kt`; modify the manifest (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`).

**Interfaces:**
- Consumes: `AudioLevel`, `ProbeStore`.
- Produces: `BootListenService` (foreground service, `foregroundServiceType="microphone"`) that, when started by `BootReceiver` on `BOOT_COMPLETED`, reads 3 s from the mic and saves `BOOT_LISTEN` with `numbers = { "started_from_boot": 1.0, "peak_dbfs": x, "seconds_after_boot": y }`. PASS only when the service started from boot **and** the audio was not silent; FAIL detail says which half failed.

- [ ] **Step 1:** Implement receiver and service; a manual "شغّل الاستماع بالخلفية" button starts the same service with `started_from_boot = 0.0` so the two cases are distinguishable in the report.
- [ ] **Step 2: Verify:** on an Android 13 emulator, reboot and open the app: `BOOT_LISTEN` shows a result with `started_from_boot = 1.0` (PASS or FAIL are both valid data; no crash, no missing result).
- [ ] **Step 3: Commit** — `git commit -m "feat: boot-time background listening probe"`

---

### Task 6: Assistant role and steering button probe (answers Q4)

**Files:** Create `probes/AssistActivity.kt`, `probes/SanafiInteractionService.kt`, `probes/SanafiSessionService.kt`, `res/xml/interaction_service.xml`; modify the manifest.

**Interfaces:**
- Produces: the app is selectable under Android's "Digital assistant app" setting (a `VoiceInteractionService` with `android.voice_interaction` metadata plus a session service), and `AssistActivity` handles `android.intent.action.ASSIST` and `android.intent.action.VOICE_COMMAND`. Any launch through either path saves `ASSIST_BUTTON = PASS` with `detail` = the action that arrived and `numbers = { "launch_count": n }`.

- [ ] **Step 1:** Implement the three components. The main screen shows a button that opens `Settings.ACTION_VOICE_INPUT_SETTINGS` so the owner can pick the app as assistant.
- [ ] **Step 2: Verify on an emulator:** set the app as assistant, run `adb shell am start -a android.intent.action.ASSIST`; the row turns PASS with `detail = android.intent.action.ASSIST`.
- [ ] **Step 3: Commit** — `git commit -m "feat: assistant-role probe for the steering wheel button"`

---

### Task 7: HUMAIN call probe with timing and echo count (answers Q5 and Q7)

**Files:** Create `net/SessionClient.kt`, `data/Settings.kt`, `ui/SettingsActivity.kt`, `probes/CallProbe.kt`; add the LiveKit Android SDK to `libs.versions.toml`.
- Test: `android/app/src/test/java/app/sanafi/probe/net/SessionClientTest.kt`

**Interfaces:**
- Consumes: backend contract from Task 2; `LatencyTimeline`, `ProbeReducer.call`, `ProbeReducer.echo`, `ErrorHints`.
- Produces:
  - `data class SessionGrant(val serverUrl: String, val participantToken: String, val sessionId: String)`
  - `class SessionClient(private val http: HttpPoster) { suspend fun start(baseUrl: String, secret: String, deviceId: String, voiceLabel: String?): Result<SessionGrant> }` where `fun interface HttpPoster { suspend fun post(url: String, headers: Map<String,String>, json: String): Pair<Int, String> }`. Failure carries `SessionError(code: String)`: backend `code` for 502, `"bad_secret"` for 401, `"network"` for any `IOException`.
  - `class CallProbe(...) { suspend fun run(voiceLabel: String?): Pair<ProbeResult, ProbeResult> }` → (`CALL_LATENCY`, `ECHO`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun parsesGrant() = runTest {
    val c = SessionClient { _, _, _ -> 200 to """{"server_url":"wss://x","participant_token":"t","session_id":"as_1","expires_at":"z","limits":{}}""" }
    assertEquals(SessionGrant("wss://x", "t", "as_1"), c.start("https://b", "s", "d", null).getOrThrow())
}
@Test fun sendsSecretHeaderAndVoice() = runTest {
    var seen: Triple<String, Map<String,String>, String>? = null
    SessionClient { u, h, j -> seen = Triple(u, h, j); 401 to "{}" }.start("https://b", "s3", "dev", "mul_abdullah")
    assertEquals("https://b/api/agent-session", seen!!.first)
    assertEquals("s3", seen!!.second["x-device-secret"])
    assertTrue(seen!!.third.contains("\"voice_label\":\"mul_abdullah\""))
}
@Test fun mapsFailures() = runTest {
    assertEquals("bad_secret", (SessionClient { _, _, _ -> 401 to "{}" }.start("b","s","d",null).exceptionOrNull() as SessionError).code)
    assertEquals("CREDITS_EXHAUSTED", (SessionClient { _, _, _ -> 502 to """{"error":"humain","code":"CREDITS_EXHAUSTED"}""" }.start("b","s","d",null).exceptionOrNull() as SessionError).code)
    assertEquals("network", (SessionClient { _, _, _ -> throw java.io.IOException("x") }.start("b","s","d",null).exceptionOrNull() as SessionError).code)
}
@Test fun neverReusesAGrant() = runTest {
    var calls = 0
    val c = SessionClient { _, _, _ -> calls++; 200 to """{"server_url":"w","participant_token":"t$calls","session_id":"s","expires_at":"z","limits":{}}""" }
    c.start("b","s","d",null); c.start("b","s","d",null)
    assertEquals(2, calls)
}
```

- [ ] **Step 2: Run** `gradle -p android testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement `SessionClient`, `Settings`, `SettingsActivity`.**
- [ ] **Step 4: Implement `CallProbe.run`.** Marks: `TRIGGER` at tap; `TOKEN_RECEIVED` when the grant returns; `ROOM_CONNECTED` after `room.connect(serverUrl, participantToken)` and enabling the mic; `AGENT_JOINED` when a remote participant carries the attribute `lk.agent.state`; `AGENT_FIRST_SPEECH` when that attribute first equals `speaking`. The call runs 40 s then disconnects (one billed minute). Echo count: every `speaking -> listening` change that happens while the local mic's level was below -50 dBFS for the preceding second counts as one `selfInterruption`; `agentTurns` = number of times the state entered `speaking`. Request audio focus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the call and abandon it after, so the owner can see whether music ducks. Also listen on data topic `humain.session` and put a `session_end` `reason` into the result detail.
- [ ] **Step 5: Run** the unit tests. Expected: PASS. Device verification is in the runsheet and needs the owner's OK because each run costs one HUMAIN minute.
- [ ] **Step 6: Commit** — `git commit -m "feat: HUMAIN call probe with latency timeline and echo count"`

---

### Task 8: Wake-word probe over music (answers Q6, with a stand-in phrase)

Phase 0 measures whether always-on keyword spotting works at all through the car microphone while music plays. It uses openWakeWord's published pre-trained "hey jarvis" model as a stand-in. Training a model for "يا سنافي" is a separate plan, written only if this probe passes.

**Files:** Create `probes/WakeProbe.kt`; add `com.microsoft.onnxruntime:onnxruntime-android`; CI step that downloads the three openWakeWord model files into `app/src/main/assets/oww/`.

**Interfaces:**
- Produces: `class WakeProbe(context: Context) { fun start(onDetect: (score: Float) -> Unit); fun stop(): ProbeResult }` — runs for a 60 s window; result numbers `{ "detections": n, "max_score": x, "avg_cpu_ms_per_frame": y }`; PASS when `detections >= 3` out of the 5 times the runsheet asks the owner to say the phrase with music playing.

- [ ] **Step 1: Resolve the integration, then record the decision at the top of `WakeProbe.kt`.** Open the READMEs of `Re-MENTIA/openwakeword-android-kt` and `msnilsen/openwakeword-android`. If one publishes a Gradle coordinate that resolves in CI, depend on it. Otherwise vendor the pipeline (melspectrogram → embedding → wake model, 16 kHz, 80 ms frames) on ONNX Runtime following the Kotlin port, keeping its licence file.
- [ ] **Step 2:** Implement `WakeProbe` and the 60 s screen with a live score bar and a detection counter.
- [ ] **Step 3: Verify on a phone or emulator with a mic:** saying "hey jarvis" five times in a quiet room gives `detections >= 4`.
- [ ] **Step 4: Commit** — `git commit -m "feat: wake-word probe with stand-in phrase"`

---

### Task 9: Telephony probe (answers Q8)

**Files:** Create `probes/TelephonyProbe.kt`; manifest `CALL_PHONE`, `READ_PHONE_STATE`.

**Interfaces:**
- Produces: `class TelephonyProbe(context: Context) { fun capabilities(): ProbeResult; fun placeTestCall(number: String) }` — `capabilities()` reports `hasSystemFeature(FEATURE_TELEPHONY)`, `TelephonyManager.phoneType`, `simState`, and whether an activity resolves `Intent.ACTION_CALL`; FAIL when there is no telephony feature or no SIM ready. `placeTestCall` is run only from an explicit button with a number the owner types; after the call screen closes the app asks "رنّ الجوال؟" and saves the yes/no as `numbers["rang"]`.

- [ ] **Step 1:** Implement and wire into the main screen.
- [ ] **Step 2: Verify on an emulator:** `capabilities()` saves a result (FAIL on a device without SIM, with a readable Arabic reason).
- [ ] **Step 3: Commit** — `git commit -m "feat: telephony probe"`

---

### Task 10: Owner runsheet and report hand-back

**Files:** Create `docs/device-runsheet-ar.md`.

- [ ] **Step 1:** Write the numbered Arabic steps: enable the `agent` scope on the HUMAIN key; set `API_URL`, `API_KEY`, `DEVICE_SECRET` on Vercel; download `sanafi-probe.apk` on the box and install; type the backend URL and secret; run the eight probes in order (MIC parked and quiet, then with AC on; reboot the box for BOOT_LISTEN; set the app as assistant and long-press the steering button; three calls with `mul_abdullah` and three with `mul_abdulaziz`, with and without music; the wake window with music at normal volume; the test call); tap "انسخ التقرير" and paste it in chat. State the cost before the call step: 6 calls = 6 billed minutes, about 3 SAR.
- [ ] **Step 2: Verify:** every `ProbeId` appears in the runsheet exactly once as a step heading.
- [ ] **Step 3: Commit** — `git commit -m "docs: Arabic device runsheet for phase 0"`

---

## What needs the owner before execution can finish

1. A GitHub repository for this project and an OK to push (CI builds the APK there).
2. An OK to deploy `server/` to Vercel and the three env vars set by him.
3. An OK for the HUMAIN test calls (about 6 billed minutes).
