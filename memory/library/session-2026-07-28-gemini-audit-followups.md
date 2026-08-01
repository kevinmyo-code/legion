# Session 2026-07-28/29: Gemini agent audit and breakfix on three commits

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Date: 2026-07-28 to 2026-07-29.

## Context

Kevin had used Android Studio's Gemini "agent" pane while the orchestrator (Stark) was unavailable.
It produced 3 commits on a new branch `feature/ui-responsiveness`, branched off `feat/cars-manager`
(a §10 branching violation: should have been off `dev`). This session audited the commits, fixed
critical defects, and shipped nine follow-up commits on `fix/gemini-audit-followups` without
pushing `main`.

**Three commits audited:**
- `147a4a1` (navigation and map corrections)
- `e948271` (Spotify transport improvements, map puck display)
- `9a690b9` (GarageHub and Cruise layout polish)

**Diff scope:** +1171 / -908 across 18 files.

## Findings: what the Gemini output got right vs wrong

### Got right

- **Frame-clock motion rule** (§12 guardrail): all animation stayed on `ui/Motion.kt` (`animPhase`,
  `triangle`, `wave`, frame-clock only). No banned `AnimatedVisibility`, `tween`, `infiniteTransition`
  anywhere. Respected the constraint correctly.

### Got wrong

**Comment deletion (impact: lost design rationale):**
- Deleted **322 comment lines** across 5 files (added 33). Notable:
  - `EmbeddedNavActivity` (602 → 410 lines): lost L1 preview-guard rationale for the `LocalInspectionMode`
    condition wrapping image loads; lost MapView lifecycle and Fragment state machine notes; lost a
    comment explaining why the full nav trip session should NOT be pulled onto the Cruise home screen —
    then the code immediately did exactly that thing.
  - `GarageHub` (numerous inline clarifications on why reactive work needed its own `remember` block).
  - Other files had context/gotcha notes erased without being replaced.

**Navigation substantially broken while claiming to fix it:**
- Full-screen embedded nav unreachable: both `start_navigation` call sites (inline in `LiveToolbox`,
  callback from voice tool dispatch) passed `openActivity = false`, so the only `startActivity` entry
  was dead. Consequence: embedded nav never launched, tool appeared to succeed, driver saw nothing.
- Voice turn guidance (spoken turn-by-turn) dead on the voice path: a conditional that should route
  to the nav session's audio output had been deleted.
- Route requested once with no retry logic: a new-route-request on first GPS fix had no fallback if
  that fix was stale or inaccurate; later fixes never re-queried. If the route request failed on the
  first GPS sample, nav stayed silent forever.
- Router `onFailure` callback was an empty block (no-op error).
- `onNewIntent` never re-routed: navigating to a NEW destination from an existing navigation session
  had no code path to cancel the old route and request a new one.

**Fabricated data on screen (impact: false state display):**
`GarageHub.kt` rendered hardcoded literal strings as if they were live state:
- Static lat/lng (Houston, ~29.76°N, 95.37°W) displayed as "current location"
- "MAPBOX READY" hardcoded (whether the user had a token or not)
- "SPOTIFY ACTIVE" hardcoded (whether Spotify was connected or not)
- "DRIVE SYNC" hardcoded (whether sync was enabled)
- "VERSION 1.0.1" hardcoded (actual version is 1.0)
- "MODE BYO-KEY" hardcoded (whether user had supplied a key or not)

**Unrequested paid image generation:**
`VehicleController` (car-facts edit flow) was firing image generation on every edit via a bare
`CoroutineScope(Dispatchers.IO).launch` (uncancellable, fires without asking, burns the user's
Gemini key quota).

**Removed `verticalScroll` after deleting its rationale:**
Cruise's COMPACT branch had a `verticalScroll(rememberScrollState())` with an inline comment explaining
that the branch contained weight-based spacing that needed vertical scroll on narrow screens. Both the
scroll and the comment were deleted, leaving the weight intact — which throws a layout crash on
preview (infinite max height issue). Fixed by removing the weight + spacing, replacing with explicit
fixed spacing.

**Half-wired map puck (impact: map puck invisible):**
A custom bitmap for the map puck was added to the map style under a name, but no layer referenced it.
The puck's position was updated in code, but the visual was never wired.

## Fixes (shipped, unverified on device)

Nine commits on `fix/gemini-audit-followups`:

1. **Restored full-screen nav entry:** both call sites now pass `openActivity = true`.
2. **Restored voice turn guidance:** re-added the voice-session audio routing.
3. **Added route retry on new GPS fix:** new route is re-requested if the driver stays in nav and a
   better fix arrives.
4. **Implemented `onNewIntent` re-route:** switching destination mid-nav now works.
5. **Implemented `onFailure` error handling:** route failures log and surface a voice notice.
6. **Fixed map puck wiring:** puck bitmap now connected to a live-updating layer.
7. **Removed GarageHub fabricated data:** all hardcoded strings replaced with live state reads.
8. **Removed unrequested image generation:** `VehicleController` no longer auto-fires restyle.
9. **Fixed Cruise COMPACT layout:** `verticalScroll` + weight removed, replaced with explicit spacing.

Also: corrected two Spotify rewrites (see lessons.md L5 for the full sequence) and two nav API calls
(signature verified via bytecode, unit tests added).

## Correction to the audit process (accuracy note)

Stark's initial audit claimed: (a) `generateMapPuck` bypassed the entitlement guard, and (b) the ETA
readout had been deleted. Both were wrong. Derek verified:
- (a) `requestImage` already gates at the choke point (`EntitlementManager.mayGenerate`), which
  `generateMapPuck` calls through the normal stack — no bypass.
- (b) The ETA readout was reimplemented inline in `NavChrome` composable, not deleted.

Both claims were corrected on verification and are NOT included in the fixes above. This happened
because the audit was done quickly and benefited from a second pass; recorded here as a note on
audit reliability (initial pass produced some overstatements, review caught them).

## Positive cadence note (L4 related)

In the one part of the session where the team dispatch cadence ran (after the Stark solo commits):
- Derek independently upgraded two `recalled` API assertions to `traced` by fetching Mapbox's live
  docs (no assumptions left unverified).
- Derek discovered the unmocked-`android.jar` constraint by empirical test probe, discovering that
  testDebugUnitTest throws on ANY `android.*` call — a whole test-design class Kevin had not
  documented.

Neither of these would have surfaced from inline work. These are the counterweight to lessons.md L4
(solo work without dispatch misses entire classes of defects; dispatch, when it runs, catches them).

## Branch state

- `feature/ui-responsiveness`: 3 Gemini commits (not merged, stale branch off `feat/cars-manager`)
- `fix/gemini-audit-followups`: 9 follow-up commits (not pushed, on-device-unverified, compile/test
  green only)
- `main`: untouched, zero pushes this session
- `dev`: untouched, zero pushes this session

Nothing merged. All verified logic-only; nine commits need on-device validation before any push.
Riskiest: nav route-retry timing, Spotify reconnect concurrency. Kevin declined ADB setup this
session.
