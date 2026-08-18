---
shelf: blocking
status: frozen
kind: blocking
tags: [library]
---

# Blocking

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


B-series blocker root-cause narratives and fix status. Maintained by the librarian.
Sprint 3 does not start until a real drive confirms every item below.

## B13 — Startup crash loop, "permissions required" with no dialog

FIXED 2026-07-08, needs device verify (confirmed root cause via code + documented Android 14
platform behavior, not a guess, but not yet run on a device). `AriaForegroundService.startForegroundCompat()`
unconditionally declared `FOREGROUND_SERVICE_TYPE_MICROPHONE` on API 30+ regardless of whether
`RECORD_AUDIO` was actually granted. `targetSdk = 34` (Android 14) hard-crashes with a
`SecurityException` when a foreground service starts with a "special use" type (microphone,
connectedDevice) declared but its permission isn't held at that moment. `MainActivity`'s
permission-request callback starts the service unconditionally even on denial (`else` branch,
by design, so a mic denial doesn't block the rest of the app), so denying RECORD_AUDIO once
crashed the app immediately. Repeated crash-restart cycles are exactly what escalates a single
denial into Android's permanent "don't ask again" state, which is why later attempts showed the
"permissions required" Toast with no visible system dialog at all: once permanently denied,
`ActivityResultContracts.RequestMultiplePermissions()` returns `false` immediately without ever
showing UI. Both symptoms were one root cause, not two. Fixed: `startForegroundCompat()` now
gates each FGS type flag on actually holding its permission right now (`RECORD_AUDIO` for
microphone, `BLUETOOTH_CONNECT` on API 31+ for connectedDevice) instead of assuming API level
alone means the permission exists; a missing grant now just means that capability is
unavailable, not a crash. Also now re-declares on every `onStartCommand()` (previously
`onCreate()`-only), so a permission granted later via Settings' retry takes effect without
needing a full process kill. A driver who already hit "don't ask again" on a prior build will
still need to clear it manually (Android Settings -> Apps -> Midnight AI -> Permissions, or
uninstall/reinstall); this fix stops the crash but can't retroactively un-deny a permission.

## B14 — Room schema/version mismatch, likely the crash that survived B13's fix

FIXED 2026-07-08, needs device verify. E5 (commit `45e97b1`) added `MonthlyRecap::class` to
`CarDatabase`'s entity list but left `version = 1` unchanged. Room only takes the
`fallbackToDestructiveMigration` wipe-and-recreate path when the on-disk database's version
number differs from the version declared in code; if the version stays the same while the
entity set doesn't, Room treats that as a same-version integrity failure (not a migration case)
and throws instead of recovering. Any device that already had a `midnight_ai_database` file from
an earlier build this session (14 entities, version 1) would crash on every single launch
opening it against the new 15-entity, still-version-1 schema, not just first run. Fixed: bumped
`version` to 2. `CarDatabase.kt`'s class doc now states the rule explicitly (bump `version` on
every entity/column change, no exceptions) so this doesn't recur next time a table's added.
Local device data will be wiped on next open (`fallbackToDestructiveMigration(dropAllTables =
true)`, expected pre-launch per the existing convention), not avoidable at this stage.

## B15 — Voice onboarding decoupled from app usability entirely

DONE 2026-07-08, needs device verify. After B13+B14, the crash reportedly persisted (possibly an
emulator-specific issue, not fully ruled out): the call was to stop trying to make voice
onboarding safe to auto-run at startup, and instead make the app usable without it ever running
at all. Structural change, not another timing patch: `OnboardingState` gained a real completion
flag split, `isComplete` (the app is usable) now flips true the moment the Gemini key is
entered, seeded with plain Yoko/Sulafat defaults (`OnboardingState.seedDefaults`), no wizard
required. A second, independent flag pair, `voiceSetupDone`/`voiceSetupDismissed`, tracks
whether the optional spoken (or typed-fallback) setup ever ran. `MainActivity` no longer gates
Cruise behind `ConversationalOnboardingScreen`/`OnboardingScreen` finishing; they're now a
full-screen overlay triggered explicitly, never auto-started. Two entry points: a dismissible
"SET UP YOUR COMPANION" tap card on Cruise (shows until completed or dismissed,
`CruiseScreen`'s new `onStartVoiceSetup` param), and a permanent "Set Up By Talking" row in
Settings (`ControlPanel`'s new `onStartVoiceSetup` param) reachable regardless of dismiss state.
`OnboardingState.reset()` now also clears both new flags, so a driver-initiated Reset still
re-invites voice setup, matching old behavior for that one path. This removes the whole class of
"voice onboarding racing permission setup" bugs by construction; it structurally can't run until
the driver explicitly taps it, by which point permissions are already settled from ordinary app
use.

## B16 — Uncaught SecurityException opening the mic without RECORD_AUDIO granted

FIXED 2026-07-08, needs device verify. Very likely the crash that survived B13/B14/B15: the
B13-era "permissions required for Yoko" Toast appeared right before it, meaning the permission
genuinely was being denied and something downstream was still crashing on that denial. Found:
`GeminiLiveSession.micLoop()` constructs `AudioRecord(...)` carrying `@Suppress("MissingPermission")
// RECORD_AUDIO requested at startup`, a comment-level assumption with no actual runtime check.
`AudioRecord`'s constructor throws `SecurityException` immediately if `RECORD_AUDIO` isn't held,
uncaught, inside a launched coroutine (`micJob = io.launch { micLoop() }`), taking the session
(and observably the app) down with it. B13 fixed the foreground-service-type declaration crash;
this is a separate crash site entirely, one level deeper, triggered by anything that calls
`startMic()` while the permission is denied, a real, common case now that B15 made voice setup
fully optional and dismissible (a driver can reach a talk-tap with mic still ungranted in ways
that were harder to hit when voice onboarding forced the permission moment first). Fixed:
`micLoop()` now checks `ContextCompat.checkSelfPermission(appContext, RECORD_AUDIO)` before
constructing `AudioRecord` and calls the existing `closeSession(...)` path instead of crashing if
it's not granted, the exact same graceful-degradation pattern the function already used two lines
down for the `STATE_INITIALIZED` check, just moved earlier to cover the permission case too.
Grepped for other `AudioRecord(` construction sites in the codebase; this was the only one.

## B17 — Actual confirmed root cause (real Logcat stack trace, 2026-07-08)

FIXED, needs device verify but this one is no longer a guess. `AndroidManifest.xml` was missing
`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE`, and, critically,
`android.permission.FOREGROUND_SERVICE` plus all three type-specific FGS permissions
(`FOREGROUND_SERVICE_MICROPHONE`/`_CONNECTED_DEVICE`/`_DATA_SYNC`) entirely. None were ever
declared, despite `MainActivity.checkAndRequestPermissions()` requesting several of them at
runtime and `AriaForegroundService` using all three FGS types. Real stack trace:
`java.lang.SecurityException: Permission Denial: startForeground ... requires
android.permission.FOREGROUND_SERVICE`, crashing every single launch at
`AriaForegroundService.onCreate()` unconditionally, independent of B13/B14/B15/B16, all of which
were real, defensible fixes for genuine problems, but none of them could have mattered here: an
undeclared runtime permission can't be granted at all (the OS denies it immediately, no dialog,
ever, which is exactly what looked like "permissions required, but no request comes up" from the
very first launch, no crash-loop escalation needed to explain it), and the missing base
`FOREGROUND_SERVICE` permission crashes `startForeground()` outright regardless of any other
permission's state. Fixed: added all 7 missing `<uses-permission>` lines. Keep B13/B14/B16; they're
still correct, needed once this manifest gap is closed. Lesson for next time a mystery crash shows
up: ask for the actual Logcat stack trace immediately, don't spend four rounds guessing from
static analysis first. The manifest gap would have been visible in under a minute from the trace
eventually pulled, versus four separate (correct, but not the actual blocker) code-level fixes
first.

Sprint 3 still shouldn't start until a real drive confirms B13 + B14 + B15 + B16 + B17 + B1/B2 below.

## B1/B2 — onboarding retry cold-start + idle-timeout false failure

DONE (code, commit `7b3b969`), needs device re-verify. Two compounding bugs found 2026-07-07:
(1) every onboarding retry cold-started from `ONBOARDING_OPENER` and a "you have no name yet"
system instruction even though captured facts (name/persona/driver/car) had already survived the
retry, fixed with `OnboardingProgress` + a resume-aware opener/instruction; (2) the actual root
cause of the drops, `armIdleTimeout()`'s 10s timeout (tuned for routine command-response turns)
was firing on completely normal thinking-pauses during onboarding's open-ended questions, and
`ChatStep` treated that benign timeout exactly like a real network failure, counting it toward
the two-strikes fallback to the typed wizard. Onboarding now gets a 45s idle timeout and treats
an `"idle"`-reason close as silently resumable, not a failure. Also: the B9/B10/B12 turn-taking
fixes (`ce696a5`, `80982cb`) apply to the same `GeminiLiveSession` onboarding uses, so they may
help further, unconfirmed. Not yet tested against the physical Cherokee since landing; treat as
unverified until a full spoken onboarding run confirms it.

## B8/F4 — in-app ELM327 scan/pair/connect UI

DONE (code), needs device re-verify. In-app ELM327 scan + pair + connect UI is built
(`ObdBluetoothManager.startDiscovery`/`bondDevice`/`unpair` + `ObdDeviceScreen`, wired into
Settings via `ControlPanelScreen`), closes the "requires Torque first" gap.

**Root causes found and fixed in commit 660208a (2026-07-09):** The pairing failure was caused by
two independent issues: (1) `bondDevice` called `setPin` twice back-to-back with "1234" then
"0000", but a pairing request only accepts one PIN reply — the second call silently overwrote the
first and only "0000" was ever tried, despite comments claiming both were attempted. Fix: send
"1234" (near-universal ELM327 default) once. (2) On Android 12+/S+, `BLUETOOTH_SCAN` permission
was missing the `neverForLocation` flag. Without it, Android silently also requires `FINE_LOCATION`
for scan results to be delivered; `ObdDeviceScreen`'s S+ permission request omitted that. Added
`neverForLocation`. (3) Connection loop hardening: `@Volatile` added to `transport`/
`connectedDeviceAddress` (read across threads); `getRemoteDevice` wrapped so a corrupt stored MAC
clears itself instead of crashing the reconnect loop. (4) Commit 660208a also found and excluded
the `companion_profile` SharedPrefs file from cloud backup (it contained plaintext Keystore-failure
fallbacks for API keys + spend passphrase hash).

Not yet confirmed against the physical Cherokee/dongle since landing; treat as unverified until a
field test explicitly exercises scan -> pair -> connect through the app's own UI.

## Image-generation budget fixes and open issues (2026-07-13)

AUDIT FINDINGS: Image-gen cost audit across the app (AvatarStudio, BackgroundGenerator, AvatarGenerator) exposed two bugs (both fixed same session) and two unresolved issues.

**BUG: Trial image budget was insufficient (FIXED 2026-07-13, commit 3d4fbab).** The trial allowance was hardcoded to 3 image API calls per action (`TRIAL_IMAGE_GENS=3` in config.ts and EntitlementManager.kt constants), but one complete avatar generation requires 8 calls: 5 from generateConcepts + 3 from deriveAndSaveStates (listening/thinking/speaking states). Portrait lazy-load adds 4 more. A trial user's first-run avatar generation would burn the entire 3-call budget on just the concepts and have zero budget left for wallpaper generation or any follow-up customization, leaving the first-run onboarding unreachable (the broken free tier entry point). Fixed: TRIAL_IMAGE_GENS bumped to 4; trial avatar generation now reuses a single chosen face for all talking states instead of deriving 3 unique states, reducing the per-avatar cost from 8 calls to ~3 (generateConcepts 5 -> 3 concepts on trial, deriveAndSaveStates copies the chosen face to listening/thinking/speaking without extra calls). This is a cost-safety fix paired with a product decision to make trial avatars "idle-only" (see decisions.md for the full scope decision).

**BUG: Subscription image usage shared with voice minutes (FIXED 2026-07-13, commit 46dda71).** Image generation on the subscription tier (sub users with monthly billing) was being metered as 1 nominal minute per call against the shared 300-min/month voice budget in entitlements.ts (`consumeImageGen` decremented `subMinutes`), while the client-side gate (`canGenerateImage`) greenlit SUBSCRIBED users unconditionally without checking the voice pool. Result: image generation appeared cost-free on the client but silently cannibalized voice minutes, and every image-set (wallpaper, avatar restyle, etc.) would partially starve a month's voice budget. Fixed: entitlements.ts now has a separate `subImageCallsUsedThisPeriod` counter; `EntitlementManager.kt` mirrors with a new `SUB_IMAGE_CALLS_CAP=24` constant (monthly ceiling) and a `subImageCallsRemaining` StateFlow; `canGenerateImage` for SUBSCRIBED now checks the image cap independently of voice minutes; image-gen on subs is metered via a separate monthly cap (sized to be cost-safe until rates are finalized before general subs launch).

**INTEGRATION GAP: Onboarding does not use the broker ephemeral-token path (NOT FIXED 2026-07-13, deferred scope).** ConversationalOnboardingScreen is BYO-key-gated (`ApiKeyGate` returns early if !GeminiKeyProvider.hasKey()), and ChatStep builds its own GeminiLiveSession defaulting to `ConnectionMode.Direct(apiKey)`. The broker's ephemeral-token path exists in LiveSessionController (`resolveConnectionMode` via LiveTokenClient.mintLiveToken) and GeminiLiveSession supports `ConnectionMode.Ephemeral`, but onboarding bypasses this entire infrastructure and never attempts to mint a trial token. This means the free-trial voice+avatar first-run path (which should be fully brokered, not user-keyed) is not yet reachable through the default onboarding flow. Kevin explicitly kept this out of scope for the economics-only pass; wiring onboarding to the broker is a separate follow-up item.

**LATENT: describePhotoForArt fails silently on metered tiers (NOT FIXED 2026-07-13, noted only).** The `proxyImage.ts` proxy on Kevin's Gemini key is used for avatar/wallpaper/mixtape-cover generation (image generation on trial/sub tiers). It allowlists only `IMAGE_MODELS` (the two image-generation models) but not `gemini-2.5-flash` (a vision model used for photo-to-wallpaper `describePhotoForArt`). When a trial/sub user tries to import a photo for wallpaper customization, `describePhotoForArt` calls the vision model, the proxy silently rejects it as unlisted, and the flow falls back to a generic description. No error is surfaced to the driver. Deferred fix: extend the allowlist to include vision models on the proxy path, or redesign the endpoint to support vision calls (both require a separate Firebase Functions deploy).

Verification: None of the above were field-tested on the Cherokee yet. The onboarding bypass and silent photo-vision failure only surface in live trial/subscription trials.

## Default voice after onboarding not persisted (2026-07-14)

FIXED, code landed and not yet device-verified. Field-test 2026-07-13 noted that the driver's chosen voice was saved post-onboarding but the service greet spoke using the default Sulafat instead of the picked voice. ROOT CAUSE: `AriaForegroundService` at boot prewarmed a `GeminiLiveSession` for the idle-ready state using `CompanionProfile.voice()` (seeded to Sulafat before the driver picks one). `LiveSessionController.prewarm()` returns early if a socket already exists (around line 136), so post-onboarding `ACTION_GREET` spoke on the stale socket instead of recreating it with the new voice. The same bug affected Settings voice changes. FIX: new `LiveSessionController.refreshIdleVoice()` calls `silentDestroy` on the idle socket + re-prewarms it so it re-reads the current voice from `CompanionProfile`. Called from `AriaForegroundService` on `ACTION_GREET` instead of calling `prewarm()` directly. BYO-key-only bug (trial/sub drivers don't eager-prewarm at boot). Also fixes live voice changes in Settings.

## B18 — Turn-taking half-duplex cut off mid-sentence (candidate fix, needs device verify)

BEST-GUESS FIX 2026-07-14, code landed, not device-verified. Field-test 2026-07-13 reported turn-taking feeling iffy (driver's speech cut off when pausing mid-sentence). ROOT CAUSE HYPOTHESIS: `GeminiLiveSession` setup sent only `automaticActivityDetection.disabled` and no timing params, so Gemini's defaults (short end-of-speech silence window) cut the driver off when they paused mid-sentence for thinking/breath. FIX: added `silenceDurationMs=900` + `prefixPaddingMs=300` to `automaticActivityDetection` in conversation mode (named constants `VAD_SILENCE_MS` / `VAD_PREFIX_PADDING_MS`). The half-duplex turn loop (`openMicForUser`/`parkWarm`/`suppressMicNextTurn`) is unchanged. This is a best-guess, not a confirmed root cause; tune the 900ms / 300ms numbers on the next drive if 900ms is still insufficient. FALLBACK if the fix doesn't work: surface turn state (mic open / bytes heard) on an on-screen debug HUD, because ADB/logcat is blocked on the head unit so existing `Log.d` diagnostics are invisible during a drive. Held back from merging into this session to avoid building blind.

## Sync engine (S1) — 2026-07-15 hunt findings

### B19 — Deleted rows resurrected on sync

FIXED 2026-07-15 (branch integration/emulator-test, commit). Soft-delete tombstone pattern fully implemented: Room v9 adds `deleted` @ColumnInfo(defaultValue="0") to car_tasks, tagged_places, and other mutable tables. DAO delete operations now UPDATE deleted=1 + clock bump (updatedAt) to enable LWW propagation. Sync SELECT * still ships all rows (deleted and alive); LWW merge in SyncMerge is unaffected (deleted rides through). Reads throughout the app filter `deleted=0` to exclude tombstones from active lists. Garbage collection of 90-day-old tombstones runs in TelemetryRecorder/MusicHistoryRecorder's existing 365-day retention loop (same pattern). The clock bump ensures tombstones propagate to Drive correctly via LWW, and single-device usage no longer resurrects purged data. Tested via integration tests; device verify pending.

### B20 — No optimistic concurrency on Drive writes

ADDRESSED (live validation pending, branch integration/emulator-test). Drive API v3 dropped v2's etag File field in favor of a `version` counter (incremented on every PATCH), but provides no server-side If-Match/412 precondition (a documented omission). Client-side version re-check implemented: DriveClient fetches the live `version` immediately before PATCH, compares to the last-seen version at sync start, and includes an opportunistic If-Match header. If versions diverge, DriveConflict.versionChanged is raised and SyncEngine.syncFile re-downloads the remote, re-merges, and retries (up to 3 attempts) before failing the table. A `findByName-before-create` fork guard prevents duplicate-file creation races. This narrows the TOCTOU race (client-side version re-check is not atomic) but does not eliminate it entirely — true atomic conditional-write is unavailable in Drive v3. DriveConflictTest (5 cases) green; device verify pending.

### B21 — LWW trusts device wall-clock, no skew protection

MAJOR, NOT FIXED. Sync uses device `updatedAt` timestamps to resolve conflicts (last-write-wins). No skew protection means two devices with drifted clocks can deadlock on the same row: Device A says "my write at 10:05 beats yours at 10:03"; Device B says "mine at 10:07 beats yours at 10:05". Each device's state oscillates on every sync, and no stable merge is ever reached. Dormant (the Cherokee's RTC should be reasonably accurate), but a cheap head unit with bad drift could hit this. Deferred pending a real-world example; possible fixes are NTP sync on boot or a server-timestamp fallback (ruled out by the "car data never leaves device" rule, §9 CLAUDE.md).

### B22 — Retention purges undone by sync

MAJOR, NOT FIXED. `TelemetryRecorder` (365-day obd_samples purge) and `MusicHistoryRecorder` (730-day music_plays purge) delete old rows. Sync then re-pulls the same rows from the remote Drive shard and re-inserts them via UNION. The purge never takes effect for sync-enabled users; DB grows unbounded. Needs a sync-aware retention pass that also purges the remote shards, or a retention-aware merge strategy that skips re-inserting deleted rows.

### B23 — syncNow not exception-safe

MAJOR, FIXED 2026-07-15 (branch fix/sync-crash-safety, commit). `SyncManager.syncNow()` opened the DB and fetched the Drive file list outside the per-table `runCatching`, then launched child coroutines via `io.launch { }` with no CoroutineExceptionHandler. A DB-open failure, permission denial, or network error during file-list fetch would throw uncaught into the io scope, and the `never-throws` contract on syncNow would be violated: the app crashes on resume. Wrapped the entire sync sequence so it honors `never throws` and individual table errors don't cascade.

### B24 — Pre-v7 rows never reconcile divergence

MINOR, NOT FIXED. Rows without `updatedAt` timestamps (pre-v7, or freshly-inserted rows on both devices) have `updatedAt=0` on both. LWW tie-breaks on `id` (arbitrary), so a genuine divergence (Device A edited the row, Device B didn't) never reconciles. Once detected this is a low-priority historical-data issue, not a regression path for new data (v7+ rows are stamped on insert). Deferred.

## Garage feature (feat/garage-shelly-cloud) — 2026-07-15 hunt findings

### B25 — Relay stuck ON if release response is lost

MAJOR, FIXED 2026-07-15 (commit). `GarageAgent.pulseRelay()` sent actuation + release as separate REST calls. If the "release" response was lost (timeout, network drop), the relay stayed energized and the door remained open. Fixed: added a `finally` block in `pulseRelay` that always attempts the idempotent release call, ensuring the relay is released even if the previous turn's response handling failed.

### B26 — Malformed 200 response treated as success

MINOR, FIXED 2026-07-15 (commit). `ShellyCloudsAPI.relayOn()` and `.relayOff()` parsed the response body without validation; a malformed JSON 200 response was treated as success instead of surfaced as a `DeviceError`. Fixed: added explicit body validation.

### B27 — Stateless voice confirm gate on relay control

MAJOR, NOT FIXED, design-level. The two-turn confirm gate (`"Are you sure? Say yes to open."` → `confirmed=true` → resolve door) has no state tracking. If the model re-invokes the same tool with `confirmed=true` after completion (re-resolving a different door, or racing with a stale confirm), the gate fires again independently. Dormant in practice (two-word confirmations rarely repeat), but architectural. Needs stateful confirm tracking (session-scoped or tied to a convo ID). Deferred for design review.

### B28 — Relay release does not survive process death

MAJOR, NOT FIXED, design-level. `pulseRelay` holds the relay energized for ~1 second in a timed coroutine. If the app is killed mid-hold (device power loss, crash, forced stop), the relay never receives the release call and stays ON. Deferred; requires hardware-level timeout or a stateful relay that can auto-release on loss-of-comms (Shelly Cloud feature, if available).

## First-run fresh-install audit (2026-07-15, session 2 findings)

Fresh-install audit revealed four issues: two critical (FIXED), two major (FIXED), and one residual minor (noted). All landed on integration/emulator-test branch; device verify pending.

**CRITICAL FIXED:** The auto-fire fresh greeting still asked the companion's name. Root cause: `AriaForegroundService.speakOpener()` used the wrong opening phrase (`ONBOARDING_OPENER` from the onboarding system instruction, not `firstGreetingOpener()` from CompanionProfile defaults). The same greeting-fix for tap-to-talk paths missed the proactive auto-fire site. Now both paths use `firstGreetingOpener()`. Residual: `markFirstSessionDone` commits unconditionally in `speakOpener`, so a silent proactive-greeting failure (broker down, no key, etc.) leaves the bundled line never to play again on that install (minor, user-favorable: no re-asking).

**MAJOR FIXED:** `OnboardingManager.voiceAvailable` was a stale one-shot remember; opening Setup immediately after a fresh install with no key would lock the entire wizard to typed-only. Now recomputes on `EntitlementManager.mode` via `collectAsState`, so the mode switch (key added -> TRIAL/BYO_KEY) unlocks the voice path live.

**MAJOR FIXED:** `LauncherSettings.openLauncherSettings/escapeToOS` had no `ActivityNotFoundException` guard (Settings.ACTION_HOME_SETTINGS not guaranteed on cheap AOSP 8-10 units). Now guarded with a Settings fallback.

**MINOR NOT FIXED:** Garage tab in Setup lands at the TOP of the section, not auto-scrolled to Garage (cosmetic). **MINOR NOT FIXED:** Broker-down trial UI (when functions/ not deployed) shows a fictional "5 min trial remaining" while every AI call fails (resolves on broker deploy). **MINOR NOT FIXED:** `OnboardingState.seedDefaults` kdoc stale (onboarding marked complete at seed time now, not at wizard finish).

## B29 — Cruise OBD/CODES tab flickers red/amber on transient read failures (2026-07-15)

CONFIRMED FINDING, NOT FIXED. Field-test drive 2026-07-15 on integration/emulator-test showed the CODES tab badge flickering between red (2 codes found) and amber (no codes) during a steady drive. ROOT CAUSE: `CruiseScreen.kt` lines 387-400 poll `dtcCount = runCatching { ObdBluetoothManager.getDtcCodes().size }.getOrDefault(0)` once per minute. A transient read failure (busy ELM327 port, comms hiccup, bad response parse) resolves to 0 via `getOrDefault(0)`, flipping the tab badge to amber; the next poll succeeds and restores the real count, flipping back to red. The defect: a READ FAILURE is silently conflated with "genuinely zero codes," breaking the user's trust in the indicator (which is the one OBD surface a non-technical driver reads at a glance). Low severity cosmetically (a minor flicker) but high impact for data reliability. FIX CANDIDATE: latch the last-known good count on a failed read instead of defaulting to 0, or require a clean zero-read confirmation before clearing the badge. Deferred pending a decision on whether to hold the last-known state or require N clean reads before changing state.

## B30 — OBD history entirely invisible in the UI (2026-07-16)

MAJOR, NOT FIXED. `obd_samples` table (365-day history of 30s PID telemetry, the stated pillar of the moat per CLAUDE.md sec 1) is WRITTEN by `TelemetryRecorder.kt` (lines 139/204/228) and READ only by voice tools (`LiveToolbox.get_trend`, `LiveToolbox.get_mpg` around lines 996/1053; `CarToolbelt` agent tools around lines 50/214) and daily/monthly recap aggregators (`DailyDriveLogController`, `MonthlyRecapController`). There is NO UI screen anywhere in the app that displays OBD history. Grep verified: `odbSampleDao()` has zero UI callers. A driver who never talks to the companion cannot see a year of their own data. Kevin's request: view it via an OBD menu / "manage OBD" screen, paired naturally with the existing OBD/CODES surface and the `ObdGauge` picker in Settings. (Note the naming quirk: DAO is `OdbSampleDao` / `odbSampleDao()`, transposed.)

## B31 — Cross-device Drive sync has no durable last-sync state (2026-07-16)

MINOR, NOT FIXED. `DriveSyncEntry.kt` holds `message` in local `remember` state only (line 53), so the sync result text disappears on recompose or navigation. No `lastSyncAt` timestamp is persisted anywhere (grep: zero hits for `lastSync`/`LAST_SYNC`). After a successful sync the driver leaves the Setup screen and returns to find the SYNC NOW button still pressable with zero indication that a sync ever happened, reading as "not done yet." The button staying pressable is correct by design (manual re-sync), but the missing persisted "last synced <time>" readout breaks the UX signal. FIX: persist `lastSyncAt` in `CompanionProfile` (SharedPrefs), show it below the button as "Last synced: <time>" instead of a transient message.

## B34 — Wake-word ListeningPhase hangs forever, no audio captured (2026-07-22)

FIXED, hardware-validated 2026-07-22 on Cherokee. Wake-word triggered correctly (UI flipped to Listening), but no audio ever got through — dead air while the companion showed "listening" and waited for mic input. Push-to-talk worked fine on the same drive, so the bug was specific to the wake-word path. ROOT CAUSE: `WakeWordEngine.triggerConversation()` fired `ACTION_TALK` without synchronously tearing down Vosk's own continuous-listening AudioRecord first. The code relied on a separate watchdog coroutine polling `ConversationState.isBusy` every 500ms (WATCHDOG_INTERVAL_MS) to release Vosk's SpeechService. But Gemini's own mic handoff (`micLoop`'s MIC_HANDOFF_MS) only waits 250ms before opening its OWN AudioRecord on the same physical mic. On a real head unit with a simple audio HAL (not a flagship phone with sophisticated concurrent-capture arbitration), two AudioRecords briefly fought over one mic and the new conversation captured only silence. PTT worked because it's almost certainly tested with Wake Word toggled off, so there was no competing Vosk AudioRecord to race against. Both paths share the exact same `onTap()`/`beginConversation()`/`micLoop()` code, there was never a separate PTT path. FIX: `WakeWordEngine.triggerConversation()` now calls `releaseSpeechService()` synchronously before firing the intent, closing the race outright instead of depending on watchdog timing (commit `98db292`). HARDWARE-VALIDATED: Kevin confirmed 2026-07-22 "nope all works as intended" on the 1998 Jeep Cherokee XJ head unit.

## B35 — Mapbox GesturesPlugin swallowed the tap-to-expand on Cruise's route map (2026-07-29)

FIXED commit 5dbc483. Tapping the embedded route map on Cruise did nothing; it was supposed to expand to full-screen nav. Location is `ui/NavPanel.kt`'s `EmbeddedRouteMap` (the map shown inside Cruise), NOT `EmbeddedNavActivity`. ROOT CAUSE: `Modifier.clickable` was placed on the `AndroidView` wrapping Mapbox's `MapView`. A native View embedded via `AndroidView` handles its own touch; Mapbox's `GesturesPlugin` owns that touch stream for pan/zoom/tap-vs-drag detection and consumes it before the Compose `clickable` on the same node ever sees a click. FIX: dropped the `clickable` and registered Mapbox's own `gestures.addOnMapClickListener`, which disambiguates a tap from the start of a pan because it is the same detector doing both. Registered on the fallback `MapView` too (so a style-load failure does not leave a dead tap target) and removed in `onRelease`; the listener reads the latest lambda via `rememberUpdatedState` because the `AndroidView` factory runs once while `onTapExpand` is rebuilt per recomposition. The API was confirmed by `javap` against the resolved SDK jar, not assumed. UNVERIFIED ON DEVICE.

## B36 — Spotify play_music foregrounded the app instead of playing in place (2026-07-29)

FIXED commit 569c40a. Reported on hardware: with "Music app on this head unit" OFF and the Spotify client ID freshly re-entered, asking for a song still launched the Spotify app and left the launcher. ROOT CAUSE (traced, not guessed): OAuth tokens are bound to the client that minted them, but `CompanionProfile.saveSpotifyClientId` never cleared stored tokens on a client-ID change. `SpotifyWebApi.isAuthorized` only checks "is a refresh token present", so it kept reporting true for credentials the new client could not use — and Setup's CONNECT guards `beginAuthorization` on exactly that check, so re-entering the ID SKIPPED the browser consent entirely. The next refresh 400'd on client mismatch, wiped the tokens, `searchTrackUri` returned null, and `playMusic` treated null as "fall back to the OS play-from-search intent", which foregrounds Spotify. Silent and self-erasing: the wipe reset the Setup status line only AFTER it had already thrown the driver out of the app once. FIX: (1) `saveSpotifyClientId` clears tokens when the ID actually changes, which re-arms CONNECT automatically since `isAuthorized` then reads false; (2) once App Remote is connected, `play_music` NEVER falls back to the intent path — it returns a spoken reason instead, distinguishing "not authorized yet, tap CONNECT" from "no match" from "Spotify refused it"; (3) search now runs BEFORE the phone stream is paused, so a failed lookup no longer silences the car to play nothing; (4) `playUri` awaits App Remote's `CallResult` instead of discarding it — `playerApi.play` returns `CallResult<Empty>` (confirmed via javap against the bundled aar), so the old code returned true on dispatch and reported "Playing <song>" over silence for every async failure (region-locked track, no active device, Premium required, stale URI); (5) the PKCE authorize request omits the `scope` parameter rather than sending it empty. NOT DONE: `searchTrackUri` still sends no `market` parameter, impact unverified. UNVERIFIED ON DEVICE.

## B37 — MainActivity declared no launchMode, so the OAuth redirect built a second launcher (2026-07-29)

FIXED commit b251978. **No crash or hang was ever observed** — this was found by static review, and the entry says so because inventing a symptom is worse than having none. `MainActivity` (the HOME/LAUNCHER activity, which also owns the `com.kevin.midnightai://spotify-callback` intent-filter) declared no `android:launchMode`, so it defaulted to `standard`, while its own doc comment asserted that an already-running instance receives the redirect via `onNewIntent`. It does not: `standard` creates a BRAND-NEW `MainActivity` (fresh `onCreate`) in the browser's task, leaving the real foreground instance stale underneath. FIX: `android:launchMode="singleTask"`. Chosen over `singleTop` because `singleTop` only routes to `onNewIntent` when the instance is at the TOP of the target task, so a redirect landing while `EmbeddedNavActivity` or `SavedPlacesActivity` was showing would still have stacked a duplicate. Reviewed for launcher blast radius: `singleTask` destroys activities above the target on redelivery, but the nav case is NOT a regression — the trip session is owned by `MapboxNavigationApp` via the process-level `NavSessionManager`, and `MainActivity` attaches it too, so clearing `EmbeddedNavActivity` leaves the route, ETA and spoken turn guidance live with Cruise's `NavPanel` still rendering it (traced, not device-verified). Also consumed the redirect (`intent.data = null`) once matched, so a later recreation cannot replay a spent PKCE verifier and toast a false failure over a connection that succeeded. DELIBERATELY NOT ADDED: `taskAffinity=""` — `EmbeddedNavActivity` and `SavedPlacesActivity` rely on sharing MainActivity's default affinity to land in its task; overriding it would fork nav into its own task. UNVERIFIED ON DEVICE.
