# Backlog: Voice

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Voice, onboarding, persona and prompt items. Maintained by the librarian.

## Quick wins

- ~~**C1** — First-person car-self prompt reframe~~ CODE DONE 2026-07-08, needs the 5 canonical
  voice tests on device before it's fully signed off. `AriaBrain.sharedInstructions` and
  `OnboardingFlow.buildOnboardingInstruction` explicitly established "you ARE this car" (previous
  wording said "speak as if you are in the car with the driver", the exact opposite framing) and
  banned third-person "the car"/"your car" self-reference. All 4 sub-agents (Diagnostic/
  Maintenance/Symptom/ColdStart) reframed from "you are the X specialist for an in-car voice
  assistant" to "you ARE the car reasoning about your own X", their raw text answers are read
  aloud verbatim, so this mattered for voice consistency, not just internal reasoning. Also:
  default companion name changed from "Moose" (an animal, personified) to "Midnight" (a neutral
  placeholder, matches the app's own name) in `VehicleController.MOOSE_PERSONA`, the default-
  vehicle seed, and the onboarding fallback. Left the existing avatar placeholder (a plain "*"
  glyph shown before any avatar is generated) as-is, it's already non-personified, no new art
  needed.

- ~~**ColdStart + Music agent optimization** — one-shot over investigate loop~~ CODE DONE 2026-07-09
  (commit bcdc870). ColdStartAgent and MusicAgent were running the full investigate loop (up to 4
  model POSTs, re-sending system prompt + tool declarations every call) despite neither needing
  adaptive tool-pulling: ColdStart is pure numeric reasoning over pre-fetched burst data; Music
  only needs google_search grounding over taste/library data already pre-seeded in the prompt.
  Added `SubAgent.askTyped`: a single-POST worker returning typed `AgentResult`, preserving
  rate-limit/bad-key/offline phrasing + KeyHealth. Grounds with `google_search` when `useSearch`
  is set (safe in one-shot, can't conflict with function declarations the way it would in a loop).
  Both agents now call `askTyped` directly: one POST instead of up to four. MusicAgent also no
  longer spawns a nested `web_lookup` sub-agent. Removed the dead `forColdStart`/`forMusic`
  toolbelt factories. DiagnosticAgent/SymptomAgent/MaintenanceAgent still use the investigate loop
  (they need adaptive tool-pulling). Net effect: lower latency + fewer tokens burned on the
  driver's own Gemini key for these two intents. No behavior change. Documented in CLAUDE.md sec
  4.2 as standing architecture note: "not every specialist uses the loop".

## OnboardingManager unified tri-modal setup (2026-07-14)

CODE DONE, merged to main (git HEAD 5e9f45c after turn-taking merge), not device-verified. Replaces the old `ConversationalOnboardingScreen` + `OnboardingScreen` (typed wizard) with a single unified flow. Steps: VOICE->NAME->PERSONALITY->DRIVER->CAR->AVATAR->BACKDROP->OBD->DONE. At NAME/DRIVER/CAR the driver can speak, tap, or type all at once (all three input methods visible per step). PERSONALITY offers a voice option or PersonaPicker; reuses VoicePicker/PersonaPicker/AvatarGenerator/BackgroundGenerator/ObdPairOnboardingStep.

**Key features:**
- Per-field voice capture: new `VoiceCaptureButton` is a scoped, on-demand `GeminiLiveSession` (mic live only during an explicit capture, no persistent listening). Uses onboarding tools (set_companion_name/set_personality/set_driver/register_car).
- Voice input on all inputs: tap/type always free; the speak option lights up only when `EntitlementManager.canStartVoice()` and mic granted (trial user with no broker completes by tap/type; mic lights up on BYO).
- All-inputs-visible per step: no sequential gate between speak/type.
- Discrete per-field capture: not a persistent session (unlike old ConversationalOnboardingScreen).
- Refactors: `resolveConnectionMode` lifted out of `LiveSessionController` into new `service/LiveConnection.kt` (internal `resolveLiveConnectionMode`, shared by onboarding + main conversation). `OnboardingState` moved from `OnboardingScreen.kt` to its own `ui/OnboardingState.kt`. Deleted `ConversationalOnboardingScreen.kt` and `OnboardingScreen.kt` entirely. `MainActivity` routes `showOnboarding` -> `OnboardingManager`; `startCompanionSetup` dropped its `hasKey` gate.

**Integration fix (resolves B-series INTEGRATION GAP):** Onboarding voice now uses `resolveLiveConnectionMode`, so it routes through the broker ephemeral-token path for trial/subscribed drivers, not just BYO-key. This fixes the gap: "onboarding bypasses the broker ephemeral-token path / only BYO-key works" (previously blocking.md, now resolved). Trial onboarding is now fully brokered; tap/type covers the no-voice case; mic opt-in is gated by `canStartVoice()`.

**Known deferred:** ai/OnboardingFlow.kt's `buildOnboardingInstruction`/`buildOnboardingOpener`/`OnboardingProgress` are now DEAD (only `ONBOARDING_OPENER` still used by `AriaForegroundService` greet); persistent-session voice continuity and a grant-mic affordance (currently just text hints) are follow-ups.

## Sprint 3 content-layer material (queue after blockers clear)

- **F3** — Adjustable voice speed + more prebuilt-voice customization

## Sprint 5 charm pass (queue for later)

- **U1** — Onboarding font + scroll cadence a la Stardew intro
- **U2** — Explicit name-confirmation step in onboarding
- **U3** — Wallpaper generation walkthrough baked into onboarding
