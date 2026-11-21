# Phase 0 build notes — decisions and progress for plan 2026-11-21-sanafi-phase0-device-probe.md
Spec: https://claude.ai/artifact/FPwL6TjPUg3Y3yPmgwwXuL (read in session)
Setup: Ruling: work on branch phase0-probe, CI triggers on every branch push — plan said push to main; skill forbids implementing on main — cost if wrong: owner merges one PR later.
Setup: Ruling: pure logic lives in a JVM-only Gradle module android/core (package app.sanafi.probe.core) so unit tests run without the Android SDK — plan put it under app/ — cost if wrong: one extra module.
Pre-flight: T1 ProbeResult/ProbeStore -> T3 consumes ProbeId/Status/ProbeResult: consistent.
Pre-flight: T2 backend contract -> T7 SessionClient: fields server_url/participant_token/session_id match; 401 bad_secret, 502 code match.
Pre-flight: T3 LatencyTimeline/ProbeReducer/ErrorHints -> T4,T5,T7 consume: signatures consistent.
Task 2: Ruling: sessions are created with fetch against POST /agent/sessions instead of the SDK's AgentClient — the REST contract is documented, the SDK's error shape is not — cost if wrong: none functionally; SDK still used for the voice list.
Task 2: Ruling: codes added beyond the plan: HTTP_<status> when HUMAIN's error body has no code, NETWORK when fetch throws, 500 server_misconfigured when env is missing, 400 for a voice_label outside the two allowed — cost if wrong: app shows a generic hint.
Task 2: Ruling: SDK pinned to 0.19.0 (npm latest) instead of 0.18.1 named in the plan — 0.18.1 came from the docs' models page; latest has the same listVoices API — cost if wrong: pin back.
Task 2: complete (commits 21f53d5..a252a15, tests: cd server && npm test → Tests 37 passed (37))
Task 1: Ruling: AGP 8.9.1 + Gradle 8.14.3 + Kotlin 2.0.21 (plan said Gradle 8.7) — matches the Gradle installed in the build sandbox so local and CI builds are identical — cost if wrong: bump one version line.
Task 1: Ruling: a debug keystore is committed (android/keystore/debug.keystore) — CI machines otherwise sign each build with a new key and the box would refuse updates — cost if wrong: it is a throwaway probe key; the product app gets a private key.
Task 1: Ruling: the report is shown on screen to be photographed, plus copy and share — the car box has no easy way to paste text into the owner's phone — cost if wrong: owner types results by hand.
Task 1: Ruling: no emulator in the sandbox, so UI behaviour is compile-verified only; device verification is the owner's runsheet.
Task 1: complete (commits a252a15..2c81cea, tests: gradle :core:test :app:assembleDebug → BUILD SUCCESSFUL, APK 8.1 MB; CI run pending push)
Task 3: Ruling: added lastMark() to LatencyTimeline and ProbeResults/ProbeCodec helpers beyond the plan's interface list — needed by the FAIL detail and the store — cost if wrong: none.
Task 3: complete (commits 2c81cea..2094f29, tests: gradle :core:test → 30 tests, 0 failures)
Tasks 4-9: Ruling: tasks 4, 5, 6, 7 and 9 landed in shared commits (00ed91e, 19d8bac, f04e1be) instead of one commit per task — the manifest, main screen and button wiring are shared files — cost if wrong: coarser history.
Tasks 4-9: Ruling: platform and screen code was written before its Robolectric tests (tests-after), then checked by mutating three behaviours and watching three tests fail — no emulator exists in the sandbox to drive a red-first UI test — cost if wrong: a screen behaviour without a guarding test.
Task 5: Ruling: boot listening takes readings through two paths (plain foreground service, and the assistant service's onReady) and passes when either hears sound from boot — Android may deny microphone access to a service started from boot; the assistant role is the path Phase 1 would use — cost if wrong: an extra reading in the report.
Task 7: Ruling: echo is measured in a separate silent call by counting how often the agent enters "thinking"; the plan's rule (speaking->listening while the mic is quiet) would count every normal end of turn — cost if wrong: echo judged on a proxy; the owner's ears are the second check in the runsheet.
Task 7: Ruling: backend gained mode "echo" (long scripted intro) and the client sends it — needed by the echo call — cost if wrong: one unused request field.
Task 7: Ruling: added a settings switch for LiveKit media audio mode — car boxes may only route media audio; lets the owner test both — cost if wrong: unused switch.
Task 7: Ruling: LiveKit 2.30.0 pinned; JitPack added for its audioswitch dependency, limited to group com.github.davidliu.
Task 1: Ruling: APK limited to arm64-v8a and armeabi-v7a (36.7 MB instead of 66.8 MB); the call library's camera and screen-capture permissions are removed.
Task 8: Ruling: NOT BUILT in this pass. The wake-word probe needs an ONNX pipeline I cannot run against real audio here, and it is moot if the microphone probe fails — the row says it comes in build 2 — cost if wrong: one more build before question 6 is answered.
Task 2: complete addendum (echo mode, commit 00ed91e, tests: npm test → 41 passed)
Task 4: complete (commits 2094f29..f04e1be, tests: gradle :core:test :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL; device run pending owner)
Task 5: complete (same range and command; device run pending owner)
Task 6: complete (same range and command; device run pending owner)
Task 7: complete (same range and command; core 54 tests + app 11 tests, 0 failures; real HUMAIN call pending owner and costs credits)
Task 9: complete (same range and command; device run pending owner)
Task 10: complete (commit d4559da, check: every ProbeId appears once as a runsheet heading → True)
Final review: self-review (no subagent dispatched: the session's rules allow spawning agents only when the owner asks).
Final: fixed "Review Focus 1 had no app-level test" — test "a call whose backend cannot be reached is saved as a network failure, not a crash" added; it passed on first run (the behaviour already existed), suite 12/12 app, 54/54 core, 41/41 server.
Final: minor (deferred): release tags (probe-N) point at the default branch commit, not the built commit; the APK asset itself is from the right build.
Final: minor (deferred): the device secret is stored in plain SharedPreferences on the box.
Final: minor (deferred): Vercel deployment of server/ (TypeScript ESM with .js import specifiers) is untested until the owner deploys.
Final: Ruling: workspace directory kept, not deleted — the owner's standing rule is to ask before deleting any folder — cost if wrong: a git-ignored scratch folder stays.
Device result (build 5): every microphone route reads -120 dBFS; the box accepts the app as the digital assistant (its service started and took a reading) but the steering wheel button never reaches it (ASSIST_BUTTON stays NOT_RUN) and opens the assistant settings page instead.
After-plan: Ruling: added a ninth probe MIC_WATCH (a 60-second foreground-service recording in half-second windows that also records whether Android is silencing the recording and whether another app records at the same time) — the device results leave one open question, whether the car microphone opens only while the button is held — cost if wrong: one extra row on the screen and in the report.
Device result (build 6, after a restart of the box): MIC PASS (-25.8 dBFS); BOOT_LISTEN PASS through the boot service (63.8 s after boot); MIC_WATCH PASS with the app as assistant (60 s in the background, never silenced, no other recorder) and silenced for 26 s while Gemini listened; CALL_LATENCY PASS, first speech 4.8-5.0 s after the tap (token 2.0-2.3 s, room 1.2 s, agent join 0.3-1.2 s, first speech 0.3-1.2 s), agent_turns=9 so the agent hears the owner; ECHO PASS in media audio mode; TELEPHONY PASS (the call rang); ASSIST_BUTTON NOT_RUN: the steering wheel button is tied to the Google app and never reaches this app. The all-silent readings of build 5 went away after the restart; cause unknown.
Task 8: Ruling: stand-in phrase is openWakeWord's "alexa" model, not "hey jarvis" — openWakeWord publishes a test recording for "alexa" and none for "hey jarvis", so the whole pipeline is verified here on a real recording — cost if wrong: none, both are stand-ins for «يا سنافي».
Task 8: Ruling: the pipeline is written in core on ONNX Runtime (compileOnly desktop build in core, Android build in app, version 1.22.0) instead of depending on a third-party Android port — it lets the same code be tested on the desktop with the real models — cost if wrong: about 100 lines of our own to maintain.
Task 8: Ruling: models are fetched at build time by android/scripts/fetch-wake-models.sh, not committed — the pre-trained models are CC BY-NC-SA 4.0 (non-commercial); a model for «يا سنافي» must be trained by us before anything is installed for other people — cost if wrong: a build that needs network access to GitHub.
Task 8: Ruling: a foreground service with a beep on each detection replaces the planned screen with a live score bar, and a second "quiet" run counts false alarms — the owner has to keep music playing, and a wake word that fires by itself would start paid calls — cost if wrong: no live score on screen.
Task 8: Ruling: plan step 3 (five calls on a phone with a microphone) is replaced by desktop tests on openWakeWord's own recordings (detected once, score above 0.9; another phrase and silence below 0.1); the test on the box is the owner's run.
Task 8: complete (tests: gradle :core:test :app:testDebugUnitTest :app:assembleDebug → 80 core, 18 app, APK built with the three models and ONNX Runtime for arm64-v8a and armeabi-v7a, 73 MB)
