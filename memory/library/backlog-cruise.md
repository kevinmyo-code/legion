---
shelf: backlog-cruise
status: frozen
kind: backlog
tags: [library]
---

# Backlog: Cruise

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Cruise screen, deck widget, gestures, Lights Out, launcher and app-tray items. Maintained by the
librarian.

## Quick wins

- ~~**B3** — Fix double app tray button rendering~~ DONE 2026-07-07, needs field verify.
  `queryLauncherApps()` wasn't deduping by package name; apps with multiple
  `ACTION_MAIN`/`CATEGORY_LAUNCHER` entry points rendered twice. Added
  `distinctBy { it.activityInfo.packageName }`.
- ~~**B4** — Fix subtitle scroll past first sentence~~ DONE 2026-07-08, needs field verify. Root
  cause: the caption UI (`CruiseScreen`'s `AvatarVibe`, `LightsOutScreen`) renders a fixed
  2-3-line box with no scroll, but `GeminiLiveSession` was emitting the entire accumulated turn
  transcript on every token; once that exceeded the box's line cap (fast), it just showed `...`
  forever while the companion kept talking, looking frozen. Fixed at the source: emits a rolling
  ~140-char tail (`captionTail`, word-boundary-snapped) instead of the full growing text, so both
  caption UIs always show what's currently being said. No scroll infra needed in either screen.

## Split-screen (superseded)

See library/archive.md — replaced entirely by R1 Companion Badge, see library/decisions.md.

## Cruise deck rework (2026-07-08)

Code done, needs device verify (no adb/AVD in this environment to check visually, layout fit and
gesture feel are unverified).

- ~~**U6**~~ Deck (cassette/vinyl/EQ) is now the dead-center focal object; the avatar moved from a
  50/50 side-by-side split to a smaller (108dp) portrait stacked below it. This arrangement was
  picked over an avatar-in-corner alternative. Wrapped the whole stack in `verticalScroll` as a
  safety net for short landscape head-unit screens, deck+track+transport+avatar is tall, and there
  was no way to confirm it fits without overflow on real hardware from here.
- ~~**U7**~~ Tap the deck object = cycle its color variant (was long-press). 2026-07-08 update:
  Vinyl and EQ styles removed entirely, cassette is the only deck object, so the swipe-up/down
  object-cycling gesture and the TAPE/LP/EQ mode indicator are gone too (nothing to cycle to).
  `NowPlayingStyle` kept as a single-entry enum rather than deleted, so the style-switching
  plumbing doesn't need rebuilding if Vinyl/EQ return; only the composables (`Vinyl()`,
  `Visualizer()`) and their variant enums/colors were actually deleted from `NowPlayingWidgets.kt`.
  Settings' "Deck style" section simplified to just the cassette colorway picker (the object
  picker and Vinyl/EQ colorway rows removed from `ControlPanelScreen.kt`).
- ~~**U8**~~ Swipe left/right on the deck = next/previous track (`MusicController`).
- ~~**U9**~~ Re-read `casette reference.png` before this pass (per CLAUDE.md's design-language
  rule). Added: translucent shell (was flat opaque color) + a diagonal glossy sheen highlight,
  write-protect notches at the top edge, two bottom locating holes, a small "C-90" tape-length
  badge, all details visible in the reference photo that were missing.
- ~~**U10**~~ Swipe down on the deck toggles `backdropMode` (ambient wallpaper vs rolling-road),
  live and persisted. `backdropMode` had to become mutable state (was read-once at composition).

New shared helper: `swipeGestures()` in `CruiseScreen.kt` (drag-distance-threshold, dominant-axis
classification), reusable if more swipe surfaces get added later.

**Follow-up fix, 2026-07-08 (first field feedback after the crash fix landed) — DONE, confirmed
working on-device.** Shrinking the avatar to 108dp for U6 left the caption overlay too small,
subtitles were cutting off again (different bug from the B4 caption-tail fix; that one fixed WHAT
text gets shown, this is about the UI box being too small to show it). Bumped `AvatarVibe` back
up to 160dp (still much smaller than the pre-U6 216x268dp default, avatar stays a secondary
element). Caption box changed from `maxLines = 3` + ellipsis truncation (silently dropped text
with no way to see the rest) to a `heightIn(max = 92.dp)` + `verticalScroll` box that auto-scrolls
to the newest text via `scrollTo` (not `animateScrollTo`, frame-clock motion only, a tween-based
scroll would just silently freeze on animator-scale-0 head units).

**Second follow-up fix, 2026-07-08 — dead tap target on the B15 "SET UP YOUR COMPANION" prompt.
DONE, needs device verify.** The card was visible but tapping it did nothing. Diagnosis: the
prompt `Row` was added to the Cruise `Box` before the U6 deck+avatar `Column`, which is
`fillMaxSize()` + wrapped in `verticalScroll` (the safety net added for short landscape screens).
Being added later, that Column draws on top (higher z-order) and its scroll gesture detector
claims touches across its entire screen bounds for drag arbitration, not just where its visible
content sits, so a tap at the prompt's screen position hit-tested against the scrollable Column
first and likely never reached the prompt's `clickable` underneath, even though the prompt was
still visibly drawn (the Column has no background painted over that region, so nothing looked
wrong). Fixed: moved the prompt block to render after the Column instead, so it's the top-most
node at its own screen position and gets the touch. Same risk could in principle apply to the
clock/dock-tile row above it (also added before the Column), not reported broken, left alone
rather than restructuring further on a guess with no way to verify from here.

**Third follow-up fix, 2026-07-08 — typed onboarding wizard was missing the wallpaper/backdrop
step entirely.** See library/backlog-visuals.md for the full write-up (`BackgroundGenerator` /
`Step.BACKDROP`).

**Fourth follow-up fix, 2026-07-08 — U7/U10 gesture mapping bug: "swipes once but can't swipe
back."** DONE, needs device verify. Root cause was a mapping choice, not a gesture-detection bug:
swipe up cycled the deck object forward (cassette -> vinyl -> eq), but swipe down was wired to
`onCycleBackdrop` (U10, toggling the ambient-wallpaper-vs-rolling-road backdrop) instead of
cycling the object backward, so swiping back down after swiping up did something invisible
(toggled the wallpaper) rather than undoing the swipe, which read as "can't swipe back." Fixed:
swipe up/down is now a real forward/backward pair over the 3 objects (`cycleObject(delta)`, wraps
both directions); U10's backdrop toggle moved to a long-press on the deck instead (`Box` went back
to `combinedClickable`, tap = cycle variant, long-press = cycle backdrop, alongside the existing
swipe-gesture `pointerInput`, same architecture as before this rework, just with the right
callback in the right slot).

**Fifth follow-up fix, 2026-07-08 — cycle got stuck on EQ, wouldn't go back to Cassette.** DONE,
needs device verify. Different bug from the fourth fix above (that one was the wrong callback in
the wrong slot; this one is a shrinking touch target). The gesture-surface `Box` had no fixed
size, so it shrunk to fit whatever object was currently shown: Cassette is 266x165dp, Vinyl is
160x160dp, but the EQ visualizer is only ~86x134dp. Once EQ was showing, the swipeable region was
far smaller than it had been for Cassette/Vinyl, a swipe in the same physical spot that worked
fine to get to EQ landed outside the (now much smaller) touch target trying to swipe away from it,
so the cycle looked stuck. Fixed: gave the Box a fixed `266.dp x 165.dp` size (Cassette's
footprint, the largest of the 3) with `contentAlignment = Alignment.Center`, so the touch target
stays constant across all three styles instead of shrinking to the smallest one.

## Cassette redesign + now-playing strip (2026-07-14)

CODE DONE, merged to main (git commit eb61854 before this session), device-unverified. CruiseScreen EXPANDED layout reworked: two equal weighted halves -> avatar moved from 50/50 side-by-side to a smaller (160dp portrait) stacked below the deck. Deck (cassette widget) now the dead-center focal object. Cassette is rendered ~1.3x via a graphicsLayer scale on a reserved-size box. Now-playing track + artist moved ONTO the cassette as a stuck-on cream label-tape strip (new `TapeStripInk` composable) that replaces the printed title band while music plays; seeker + transport controls below. COMPACT layout keeps 1x cassette + the strip. Cassette details updated to match reference photo: translucent shell (was flat opaque), diagonal glossy sheen highlight, write-protect notches at top edge, two locating holes at bottom, small "C-90" tape-length badge.

## PENDING GRILL 2026-07-16 — Cruise UI maximum customization (home-screen model)

**UNRESOLVED IDEA, NOT DECIDED.** Let users configure their own Cruise layout like a phone home screen: custom background (user's own photo from Drive, or AI-generated), movable widgets that lock in place after positioning. The cassette widget can move around; possibly reinstate vinyl / boombox widget variants. Avatar can move too; lock after moving (phone-home-screen edit/lock mode). Goal: maximum customization, full reign over the Cruise surface. **REFRAMED 2026-07-16 by positioning decision:** This is no longer only a coherence-law tension. The app is now positioned as a launcher competing on looks—a single authored aesthetic is the entire wedge. A theme system means having no look. Positioning on looks and shipping a theme system are close to mutually exclusive. The question is sharpened: do we want one authored look (and reject customization) or do we want theme flexibility (and trade the only confirmed differentiator in the category)? Map tickets 05, 08, 09 stay open for customization scope. Marked PENDING GRILL for product/design decision.

## PENDING GRILL 2026-07-16 — Settings redesign (interactive garage scene)

**UNRESOLVED IDEA, NOT DECIDED.** Redesign the settings UI as an interactive garage; settings become tappable objects in a scene rather than a flat list. Kevin, 2026-07-16: "settings menu is bland and just a list. lets make it into an interactive garage (think Tokyo Drift garage), users car on hoist, menu options will be objects." NOTE: Reference drifted from "cozy garage" (2026-07-15) to "Tokyo Drift garage" (2026-07-16)—different rooms, needs settling. Well-founded because §12 already says "parked surfaces RICH" and settings is a parked surface, and because the Logbook's paper surface is precedent for a second material. **Open feasibility problem:** "user's car on a hoist" needs their actual car rendered, and generated-per-car is Gemini-billed so the free tier's first settings screen would have an empty hoist. Bundled body-style silhouettes keyed off `vehicle_spec` are the likely answer, but a driver seeing "a red 4-door that isn't quite my XJ" may feel worse than an honest generic shape. Marked PENDING GRILL pending feasibility + design resolution.

## Trivia Game (companion-driven in-car trivia, paid-tier only) — SHIPPED 2026-07-22

CODE DONE, not device-verified. Feature-branch: `feat/cars-manager`. Wayfinder planning map at `.scratch/trivia-game/` (tickets 01-06 all resolved). A turn-based trivia game mediated entirely through the voice assistant during a drive.

**Architecture:**
- **Tier:** Paid-only, rides on existing $10 BYO-key unlock; no new gating.
- **Persistence:** None. Ephemeral in-memory only, no Room table, no recap/Wrapped surfacing.
- **Speaker diarization pattern:** No diarization exists in the audio pipeline (single mic, half-duplex STS). Moose asks "who got that, driver or passenger?" out loud after each question and scores off the spoken reply. Reusable pattern for any future multi-occupant feature.
- **Voice tools (three new LiveToolbox tools, always-declared):**
  - `start_trivia_game(category, question_count, driver_name, passenger_name)` — init game state
  - `award_point(who: "driver"|"passenger")` — increment score for winner of the current question
  - `end_trivia_game()` — stop and show final scores
  - No skip/advance tool; question progress tracked conversationally by Moose (the LLM), not by app-side counter. Scoreboard UI does NOT display question progress.
- **UI:** `ui/TriviaScoreboard.kt` composable overlay:
  - Cruise: top-right amber card (TriviaOverlay) with scoreboard + names + points
  - Lights Out: bottom-right dim-magenta/amber card
  - Frame-clock glow via `Motion.kt` (triangle/animPhase) on leading score
  - Celebration state reuses cream lower-third caption bar (existing pattern from ScreenSaverScreen.kt)
- **Bounds/Safety:** Soft 5-15 question cap lives ONLY in system instruction (AriaBrain.kt sharedInstructions); no code-side validation. Ephemeral + no re-engagement hook = §9.1 win (pushes toward real-world passenger connection, not AI dependency).
- **Lifecycle:** Active game counts as "busy" for proactive-gating purposes only (idle chatter / weather alert / arrival reminder hold off). Implemented as `|| TriviaController.isActive` at specific call sites in AriaForegroundService (drive-monitor loop, onArrived), not wired through ConversationState.setBusy. Urgent alerts (overheat, fresh DTC) fire normally. Game auto-ends (with celebration) when drive monitor detects drive stopped; final score is whatever it stood at. Opening nav/music via Companion Badge does NOT pause game (scoreboard just isn't visible while another app foreground, resumes on Home).
- **Controller:** `service/TriviaController.kt` holds `StateFlow<Game?>` (triggers UI recompose on point) + separate `SharedFlow<Game>` called `ended` (drives celebration overlay timing, distinguishes "just ended" from "never ran").
- **Prototype validation:** Proto commit on `proto/trivia-scoreboard` (NOT merged) validated shape/palette/motion before real implementation; prototype excluded question-progress display per final design.
- **CLAUDE.md updates:** Sec 4.3 tool table + sec 15 codebase map both updated with three new tools and two new files.

**Note: Wayfinder/grilling workflow used end-to-end this session (plan → tickets → prototype → build) successfully; viable pattern for future feature scoping.**

## Sprint 5 charm pass (queue for later)

- ~~**U4** — Configurable Lights Out mode~~ DONE 2026-07-08, needs device verify. Gauge visibility only (color settings were already covered by the pre-existing app-wide palette picker, `AriaPalette`, which propagates live into `LightsOutScreen` via `AriaColors` delegation). Added 4 SharedPreferences boolean flags to `CruiseSettings` (lightsOutShowSpeed/ShowRpm/ShowCoolant/ShowVolts, all default true), wired into `LightsOutScreen.kt`'s telemetry-footer `fields` builder; added "Lights Out gauges" section in `ControlPanelScreen.kt` with 4 Switch rows (Speed/RPM/Coolant/Voltage), placed after the existing "Color palette" section. Both controlled via the same `flag`/`setFlag` pattern as existing CruiseSettings toggles.
- ~~**U5** — Nightmode button bigger, more accessible from Cruise~~ DONE 2026-07-08, needs field
  verify. Moved from the bottom-right corner (beside app tray) to bottom-left beside the logbook;
  replaced the plain dim-glyph `CruiseButton` styling with a solid `NightModeButton` (52dp, solid
  panel fill, full-brightness amber ring) so it's actually findable/hittable by feel at night, per
  a real driving-session complaint.
