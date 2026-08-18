---
shelf: hardware
status: frozen
kind: hardware
tags: [library]
---

# Hardware Validation

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Device validation ledger. Maintained by the librarian. See CLAUDE.md sec 14 for the full pending
list framing. Field-test session 2026-07-05 completed against Cherokee + head unit.

## Validated as failing

- B1, B2: onboarding voice flow (unreliable, non-resumable) — code fix landed, see library/blocking.md
- B3: double app tray buttons — fixed, see library/backlog-cruise.md
- B4: subtitles freeze after first sentence — fixed, see library/backlog-cruise.md
- B5: rolling road generation fails — see library/backlog-visuals.md
- B6, B7: wallpaper cropping in Appearance + generation output — fixed, see library/backlog-visuals.md
- B8: ELM327 raw pair fails without Torque — code fix landed, see library/blocking.md

## Field-test session 2026-07-13 (Kevin drove Cherokee + head unit before onboarding manager landed)

Test date: 2026-07-13. Build: pre-OnboardingManager (two separate onboarding screens still in place). Test vehicle: 1998 Jeep Cherokee XJ. Test dongle: Bluetooth ELM327 RFCOMM. Tester: Kevin.

**Findings and fixes:**

1. **Double voice selection in typed onboarding:** ROOT CAUSE FIXED by OnboardingManager rewrite. The old typed wizard and spoken ConversationalOnboardingScreen both had VOICE steps, so if a driver hit "set up by typing," they'd be asked to pick a voice twice (once in each screen). OnboardingManager collapses to a single unified VOICE step at the start, eliminating the double ask.

2. **Cassette redesign + now-playing strip:** Build landed 2026-07-08 (commit eb61854), tested 2026-07-13. Cassette now 1.3x scaled, now-playing track+artist stuck on cream TapeStripInk label strip; avatar moved to smaller portrait stacked below. Layout felt good in the car, no overflow observed, seeker/transport controls readable and reachable.

3. **OBD status:** Works great, no changes needed. Bluetooth RFCOMM stable. Codes parse correctly. No new issues identified.

4. **Turn-taking half-duplex:** Noted as iffy (driver's speech cut off when pausing mid-sentence). Best-guess fix applied 2026-07-14: VAD tuning (silenceDurationMs=900, prefixPaddingMs=300). Not yet re-tested; tune on the next drive if 900ms insufficient.

**Deferred/unverified:**
- OnboardingManager voice-capture integration (landed post-test).
- Cassette strip visual polish (reference-photo matching already applied).
- Full onboarding flow including the new VOICE->NAME->...->OBD sequence.
- Album cover generation workflow (landed post-test).

**Next drive:** Should exercise the full OnboardingManager flow including deliberate pauses >10s on open-ended questions (to re-verify B1/B2 resume fix), full VAD/turn-taking through a real conversation, and album/cover-art creation workflow.

## Test vehicles (as of 2026-07-12)

**Primary target vehicles:**
- **1998 Jeep Cherokee XJ:** Head unit (car-mounted Android head unit, Bluetooth ELM327 RFCOMM + OBD).
  Validated to date: voice loop (PTT -> Gemini Live STS), OBD codes parsed, weather chat, Spanish tutoring.
  Still pending: all Sprint 1/2/3+ features listed in "Pending validation" below.
- **2020 Mitsubishi Outlander:** Kevin's daily driver (no aftermarket head-unit screen). Phone-only build
  test rig, becomes primary OBD validation target going forward (replacing Cherokee commute validation).
  Validation target: [[backlog-obd#BLE OBD ELM327 support]] on Kevin's FlyRoadTech BLE ELM327 dongle,
  dual-target phone reflow, OBD live + history/trends/MPG. Does not replace Cherokee as the head-unit
  validation platform; both are needed per the architecture (head unit is the fixed-landscape "home
  screen" profile, phone is the responsive profile).

## Field-test session 2026-07-15 (integration/emulator-test branch, S1 sync first pass)

Test date: 2026-07-15. Build: integration/emulator-test (post-sync-fix crash safety and soft-delete landings). Test vehicle: 1998 Jeep Cherokee XJ. Tester: Kevin.

**Findings:**

1. **S1 Drive sync partially validated on head unit (first on-device evidence).** The head-unit side of the cross-device sync engine (Google Drive authorization flow, light-data upload) successfully ran during a real drive. Kevin confirmed: head unit uploaded to Drive without error, the authorization flow ("connect Google Drive") was easy UX. First concrete evidence that S1 is functional on the physical head unit (previously 100% unvalidated post-code-landing). Phone-side sync + two-device round-trip validation still pending (Kevin was installing on phone same session).

2. **CODES tab flicker observed (new bug B29).** See blocking.md B29.

**Deferred/unverified:**
- Phone-side sync completion
- Two-device reconciliation (data syncing back from phone to head unit)
- Full sync-cycle robustness under sustained usage

## Field-test session 2026-07-16 (two-tier pricing, Zero mascot, canned voice)

Test date: 2026-07-16. Test vehicle: 1998 Jeep Cherokee XJ. Tester: Kevin.

**Findings:**

1. **OBD and voice work fine.** Kevin verified on the Cherokee: "all obd and voice and stuff works fine." **Recorded as PARTIAL** - he has not confirmed which of the specific MEMORY.md checklist items (B13-B17, B8/F4, B1/B2, C1's five canonical voice tests, M1/M2/M3, Companion Badge, Lights Out backlight) he exercised. Do not mark those individually verified from this session. Next drive should isolate each.

## Pending validation

- Sprint 1 key gate on head-unit keymaster
- Sprint 2 obd_samples writes while idling on 1998 XJ
- Mode-02 freeze frames on real codes
- MPG_TRIP MAF integration on real drive
- Cold-start burst captures
- `check_readiness` monitor states
- Phone BT music transport + AVRCP album art
- Telephony HFP call-state
- Launcher set-as-home behavior (head unit)
- Lights Out backlight drop (head unit)
- Waze launch via intent
- Recall proactive announce
- Full-duplex audio
- Companion Badge (replaces split-screen paths, which no longer exist as of R1, see library/decisions.md)
- Dual-target phone reflow (Material3 WindowSizeClass, COMPACT layout adaptation)
- Camera capture + gallery import on phone (car photo feature, gated on FEATURE_CAMERA_ANY)
- BLE ELM327 dongle (Outlander + FlyRoadTech HM-10 device, see backlog-obd.md)

## Open / unverified (2026-07-25 GPS beacon feature)

Nothing in the beacon path has run on hardware. Specifically unverified:
- Whether Mapbox Nav SDK actually invokes our `BeaconLocationProvider` at runtime
- UDP beacon link end-to-end (head unit hello, phone transmit, Mapbox consumes updates)
- Whether `getLastLocation` never invoking its callback (when no GPS fix exists) hangs anything inside
  the SDK — rated PLAUSIBLE by Vic, unconfirmed; `EmbeddedNavActivity`'s own no-fix timeout would
  likely mask it if it were a blocker
- GL ES 3.0 capability value on the Cherokee remains unread (gates embedded nav entirely)

All four above are first-drive validation targets once feat/cars-manager merges.

## Session 2026-07-28: Kevin on phone (Oppo A17K), audit of Gemini agent work

Test date: 2026-07-28. Test vehicle: Oppo A17K phone (COMPACT ~360dp), occasional Cherokee head unit.
Tester: Kevin. Build: `feature/ui-responsiveness` (Gemini agent's 3 commits), then
`fix/gemini-audit-followups` (Stark's 9 fix commits). Kevin's observations below are from the FORMER;
none of the fixes have run on a device.

**Posture shift (stated):** Kevin is now phone-first and expects to stay that way. The COMPACT window-
size class (media queries ~600dp width and below) was de facto secondary behind EXPANDED (landscape
head unit) — it is now the daily-used, must-work-daily surface. Header-unit validation is still
critical (launcher integration, Lights Out, the killer moment), but device-unvalidated work lands on
COMPACT first.

**Confirmed working 2026-07-28 — these are Kevin's exact reported observations, nothing inferred:**
- Saving places by voice (`tag_place`)
- Navigation starts, and the embedded Mapbox map is visible on Cruise

CORRECTED 2026-07-29: the first filing of this entry also listed "puck tracking in place" and
"Companion Badge visible when nav is fullscreen". Kevin reported NEITHER. He said the opposite on
both counts - the custom puck "indeed doesnt show", and full-screen nav was gone entirely, so there
was no fullscreen state for a badge to appear over. Both were fabricated confirmations. Do not treat
anything in a "confirmed working" list as observed unless it traces to something the tester actually
said.

**Confirmed broken at session start, fixed (code-committed), UNVERIFIED on device:**
- Full-screen embedded nav completely unreachable (`start_navigation` had `openActivity = false` in both
  call paths, so the only `startActivity` entry was dead)
- Map puck (custom bitmap) was added to map style but never wired to a layer (half-wired)
- Spotify play-by-voice failed with a misleading error message (re-created original bug through a new
  code path, see lessons.md L5)

**Riskiest (compile-verified only):**
- Nav route retry-on-first-fix (timing-dependent state machine)
- Spotify reconnect path (concurrency-dependent, threading-boundary state, see lessons.md L5)

**Verification status:** All nine commits (`fix/gemini-audit-followups`) are compile-green and
unit-test green. ZERO on-device validation. Kevin declined ADB setup
("i havent set up ADB ive been lazy, dont think i'll do it tonight either"). Next drive will be
the first on-device validation of the nav and Spotify rewrites, and the first load on the COMPACT
surface stability fixes (weighted composables, positioned list dedup, recomposition metrics).
