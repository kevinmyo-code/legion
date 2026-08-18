---
shelf: sprints
status: frozen
kind: sprints
tags: [library]
---

# Sprints

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Sprint 0-6 status snapshot and Sprint 3 active scope detail. Maintained by the librarian.
See CLAUDE.md sec 6 for the frozen sprint plan.

## Snapshot

- Sprint 0 (code done 2026-07-02): package rename `com.kevin.nightrunner`; Room clean v1;
  AriaPalette wired. Open non-code: mascot art, trademark check, bundled assets, Firebase
  re-register.
- Sprint 1 (done 2026-07-02, hardware-unvalidated): paid-app billing; FirstRunScreen key gate +
  1-token ping; KeyVault Keystore AES/GCM; proxy + TokenBudget deleted; defaults = Yoko / Sulafat.
  Field-test flagged B1/B2 as blockers (see library/blocking.md).
- Sprint 2 (done 2026-07-02, hardware-unvalidated): ObdResponseParser + 7 PID parsers; 36 unit
  tests green; TelemetryRecorder; MPG via MAF integration; cold-start burst; 4 new tools +
  ColdStartAgent. Field-test flagged B8/F4 as blockers (see library/blocking.md).
- Sprint 3 (next): Content layer. See "Sprint 3 scope" below. Not started, blocked on Sprint 1/2 hardware validation.
- Sprint 4: Distribution (logbook CODES tab, Build Card export, cinematic mode, Crashlytics).
- Sprint 5: Charm (custom fonts, UI sound catalog, ritual micro-animations, voice sample clips).
  See library/backlog-voice.md, backlog-cruise.md, backlog-visuals.md for tagged items.
- Sprint 6: Wrapped + launch. Yearly Wrapped v0 started 2026-07-08, see library/backlog-recaps.md.

**2026-07-14 session updates (main branch, build-green, device-unverified):**
- OnboardingManager unified tri-modal setup (replaces two old screens); integration gap fixed (onboarding now uses broker ephemeral-token path). See backlog-voice.md.
- Cassette redesign + TapeStripInk now-playing label. See backlog-cruise.md.
- Named/trip photo albums with AlbumCoverGenerator. See backlog-visuals.md.
- Default voice after onboarding fix (refreshIdleVoice). See blocking.md.
- B18 turn-taking VAD tuning candidate fix (silenceDurationMs=900). See blocking.md.
- image-gen-economics branch (2 commits): trial avatar cost reduction + subscription image-call cap. Not yet merged to main. See backlog-visuals.md.

## Sprint 3 scope

Chassis-quirk bundled YAML -> Room + QuirkAgent + tools; oil-analysis (Blackstone UOA) entry form
+ trend tools; Foresight nightly aggregation (Gemini-as-reasoner over obd_samples).

Not started. Blocked pending resolution of Sprint 1/2 hardware validation gaps
(library/blocking.md: B13-B17, B1/B2, B8/F4).

## Verification drive checklist (updated 2026-07-14)

- R1 done (2026-07-08): Companion Badge shipped, replacing split-screen entirely. Next Cherokee
  drive should confirm the badge shows on `start_navigation`/`open_music`, tap-to-talk and
  transport work through it, and it actually disappears on Home (never verified on the physical
  unit).
- C1 patch: ship after `gradlew assembleDebug` clean + 5 canonical voice tests on device (test
  cases in the C1 patch doc).
- B8/F4: code landed (`ObdBluetoothManager.startDiscovery`/`bondDevice` + `ObdDeviceScreen`), no
  longer needs a dedicated build session. Next Cherokee drive should exercise scan -> pair ->
  connect through the app's own Settings UI to confirm it actually works end-to-end (never
  verified on the physical unit).
- B1/B2: code landed (`7b3b969`), next Cherokee drive should run a full spoken onboarding,
  including deliberately pausing >10s on an open-ended question, to confirm the resume + 45s idle
  timeout actually fix it. Same drive should also exercise the B9/B10/B12 turn-taking fixes
  (proactive-tap barge-in, B10 mic settle delay, B12 audio-queue decoupling), all speculative
  except the barge-in fix, all still needing field confirmation.
- **2026-07-14 additions:** Next drive should also exercise (1) full OnboardingManager tri-modal setup including VOICE->NAME->...->OBD sequence with per-field voice capture; (2) B18 VAD tuning (turn-taking with deliberate mid-sentence pauses, tune 900ms if needed); (3) cassette redesign + now-playing strip visual integration; (4) album creation + cover art generation workflow.
- Field-test cadence: the code-side blockers are now all patched pending verify; the next
  Cherokee session is a real verification drive, not blocked on more fixes landing first.
