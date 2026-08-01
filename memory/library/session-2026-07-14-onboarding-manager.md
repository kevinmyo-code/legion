# Session 2026-07-14: Onboarding Manager + Field-Test Fixes

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Session date: 2026-07-14. Git state: onboarding-manager (10 commits) + image-gen-economics (2 commits, not merged) + turn-taking-vad (1 commit) merged to main. NOT pushed to origin (origin/main still at eb61854). app/libs/ still untracked. All local builds: BUILD-GREEN (gradlew assembleDebug + testDebugUnitTest pass), DEVICE-UNVERIFIED (headless dev shell).

## Onboarding Manager: Unified Tri-Modal Setup

Replaced old two-screen setup (ConversationalOnboardingScreen + OnboardingScreen typed wizard) with single unified `OnboardingManager.kt`. Steps: VOICE->NAME->PERSONALITY->DRIVER->CAR->AVATAR->BACKDROP->OBD->DONE.

**Per-step input model:** At NAME/DRIVER/CAR, driver can speak, tap, or type all at once (all three visible). PERSONALITY offers voice option or PersonaPicker. Reuses VoicePicker/PersonaPicker/AvatarGenerator/BackgroundGenerator/ObdPairOnboardingStep.

**Voice capture:** New `VoiceCaptureButton` is a scoped, on-demand `GeminiLiveSession` (mic live only during explicit capture, no persistent listening). Uses onboarding tools (set_companion_name/set_personality/set_driver/register_car). Tap/type always free; speak lights up only when `EntitlementManager.canStartVoice()` and mic granted (trial with no broker completes by tap/type).

**Refactors:**
- `resolveConnectionMode` lifted into new `service/LiveConnection.kt` (internal `resolveLiveConnectionMode`, shared by onboarding + main conversation).
- `OnboardingState` moved from `OnboardingScreen.kt` -> `ui/OnboardingState.kt`.
- Deleted: `ConversationalOnboardingScreen.kt`, `OnboardingScreen.kt`.
- `MainActivity` routes `showOnboarding` -> `OnboardingManager`; dropped `hasKey` gate from `startCompanionSetup`.

**Integration fix (resolves INTEGRATION GAP from blocking.md):** Onboarding voice now uses `resolveLiveConnectionMode`, routing through broker ephemeral-token path for trial/subscribed drivers, not just BYO-key. Trial onboarding is fully brokered; tap/type covers no-voice case; mic opt-in gated by `canStartVoice()`.

**Deferred:** `ai/OnboardingFlow.kt`'s `buildOnboardingInstruction`/`buildOnboardingOpener`/`OnboardingProgress` now DEAD (only `ONBOARDING_OPENER` used by service greet). Persistent-session voice continuity and grant-mic affordance deferred.

## Field-Test Session 2026-07-13

Test vehicle: 1998 Jeep Cherokee XJ. Dongle: Bluetooth ELM327 RFCOMM. Tester: Kevin. Build: pre-OnboardingManager (old two screens).

**Findings:**

1. **Double voice selection in typed onboarding:** FIXED by OnboardingManager rewrite. Old wizard + ConversationalOnboardingScreen both had VOICE steps; driver picking typed path got asked twice. OnboardingManager single VOICE step at start eliminates the double ask.

2. **Default voice after onboarding not persisted:** FIXED, code landed. Root cause: `AriaForegroundService` prewarmed idle socket at boot using default Sulafat before driver picked voice. `LiveSessionController.prewarm()` early-exits if socket exists, so post-onboarding greet spoke stale socket, not new voice. Fix: new `refreshIdleVoice()` destroys idle socket + re-prewarms with current voice from `CompanionProfile`. Called from `AriaForegroundService` on `ACTION_GREET`, also fixes Settings voice changes. BYO-key-only bug.

3. **Cassette redesign + now-playing strip:** Layout tested 2026-07-13 (build landed 2026-07-08). Cassette 1.3x scaled, now-playing track+artist on cream TapeStripInk label strip; avatar 160dp portrait below. Felt good in car, no overflow, seeker/transport readable. Cassette details (translucent shell, glossy sheen, notches, holes, C-90 badge) match reference photo.

4. **OBD status:** Works great, no changes.

5. **Turn-taking half-duplex iffy:** Driver's speech cut off mid-sentence. BEST-GUESS FIX applied 2026-07-14: VAD tuning `silenceDurationMs=900` + `prefixPaddingMs=300` (named constants `VAD_SILENCE_MS`/`VAD_PREFIX_PADDING_MS`). Not re-tested; tune on next drive if 900ms insufficient. Fallback: surface turn state on debug HUD (ADB/logcat blocked on head unit, so existing Log.d invisible during drive).

## Code Changes Summary

**Merged to main (2026-07-14):**
- OnboardingManager + OnboardingState refactor (10 commits).
- refreshIdleVoice() + default voice fix (in onboarding-manager branch).
- Cassette redesign + TapeStripInk (commit eb61854, already merged earlier).
- Named/trip photo albums + AlbumCoverGenerator (commit eb61854, already merged earlier).
- B18 VAD tuning attempt (1 commit, turn-taking-vad merged after onboarding-manager).

**Not merged (pending):**
- image-gen-economics branch (2 commits, 3d4fbab + 46dda71): trial avatar cost reduction (generateConcepts 5->3, deriveAndSaveStates reuses face), subscription image-call cap (SUB_IMAGE_CALLS_CAP=24), backend config sync. Gradlew builds clean; no new unit tests. Deferred: broker deploy, monthly reset logic, cost-number finalization, on-upgrade lazy portrait fill. See backlog-visuals.md for full detail.

## Firebase

Confirmed accessible via firebase MCP (project midnight-ai-c7421). Crashlytics release-build gated; debug test-drive had no crashes to report.

## Deferred: Logbook Front-Page Car Polaroid

Feature #5: auto-generated logbook front-page car polaroid (real photo or generated from year/make/model/trim via `AvatarStudio.generateCarPortraitFromText`). Plan pending.

## Status

Main branch: build-green, all 36 unit tests pass, not pushed to origin. Next: drive-test of full batch (OnboardingManager flow, cassette, albums, VAD tuning, B1/B2 resume fix, B8/F4 scan->pair->connect).
