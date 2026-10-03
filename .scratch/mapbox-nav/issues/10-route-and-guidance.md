---
map: mapbox-nav
ticket: "10"
title: "Route and guidance on the nav screen"
type: build
status: built
status-detail: "Built 2026-10-03, suite green bar a known Room race; owes a run on the phone (list in Built)"
blockers: ["03", "06", "07", "09"]
blocked-by: ["[[03-resolving-a-spoken-destination]]", "[[06-the-nav-screen]]", "[[07-trip-lifecycle]]", "[[09-sdk-in-the-build]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Route and guidance on the nav screen

## Build

The nav screen from 06, with a ViewModel. Destination resolution per 03, route preview, active
guidance, arrival, cancel. Lifecycle per 07. Hands path only; voice is 11.

## Verification

- Unit: destination resolution order, trip session start/stop guard.
- On the phone: route to a saved place and to a searched place, cancel mid-trip, screen off
  mid-trip, arrive. No trip session left running afterwards (check the Mapbox dashboard's trip count).

## Spike findings to carry in (A25, 2026-10-03)

The spike (`feat/mapbox-nav`, `navigation/MapboxNavController.kt`, `ui/navigation/NavSpikeScreen.kt`)
ran on the phone:

- **on-device:** tiles render; a route came back in about 0.4 s from the live fix to the saved `home`
  place; guidance text populated ("Turn left", "116 mi left, 1 h 59 min"); Stop and backing out both
  stop the trip session, and the SDK's `NavigationNotificationService` and its notification are gone
  afterwards. No crash.
- **on-device, a gap:** no route line and no location puck in view. The camera is fixed on downtown
  Houston and never follows or fits the route. `NavSpikeScreen` also skips drawing the route line if
  the style has not loaded yet and never retries (reasoned cause). This build needs camera follow,
  fit-to-route in preview, the puck, and a route line drawn after the style loads.
- **on-device, open:** the SDK starts a Free Drive session for about 8 ms before Active Guidance
  inside the same call. Mapbox's 30 s grace period should keep it unbilled (reasoned); check the
  dashboard's trip count after a few test runs.
- **on-device, noise:** `ThemeUtils` wants an AppCompat theme for the compass, logo and attribution
  views under a Compose-hosted `MapView`; the map still renders. Fix with a themed context.

## Built (2026-10-03)

**Controller, outlives the screen (07).** `navigation/MapboxNavController.kt` is one app-owned
instance (`MidnightApplication.navController`, beside `mapboxTokens`), constructed over a seam
(`navigation/NavSdk.kt`) so every rule is unit-tested against a fake. `MapboxNavSdk.kt` is the one
real seam; `NavMapFeed` hands the map the raw routes and progress. A trip ends only on arrival,
`end()`, a token change or process death; `onScreenLeft()` never ends GUIDING. Billing guard
(`NavTripGuard`): session only in GUIDING, started only after set routes, stopped BEFORE routes are
cleared, instance destroyed on every way out. No free drive.

**API = the four tools' verbs (04).** `preview`, `navigate(previewOnly)`, `start`, `addStop`,
`removeStop`, `setAvoid` / `toggleAvoid`, `takeAlternative`, `setMuted` (state only), `overview` /
`recenter`, `end`, `status()`. Every mutating call returns `NavResult(ok, message)` with `ok` read
from the SDK AFTER the call (`activeRoutes()`, `isSessionRunning()`); a refused change says the trip
is unchanged; `status()` with no trip is `NotNavigating`, and unknowns are null.

**Destination resolver (03).** `navigation/resolve/`: saved places, calendar, contacts, then Mapbox
search through the Search SDK 2.32 (`mapbox-search-android-ndk27`, anonymous, +1 dependency, no
conflict; no REST). `createSearchEngine` (not the built-in-providers form, which keeps a local
history) so nothing from Mapbox is stored. Unreadable and empty are different sentences for each
source. Ambiguity flag drives the read-back card.

**Screen: layout A (06).** `ui/navigation/` (`NavScreen`, `NavSheets`, `NavPanels`, `NavBanner`,
`NavMap`, `NavViewModel`, `NavActions`); replaces the spike screen and its diagnostics row. Route
`navigate` (optional `place` argument, `LegionRoute.NAVIGATE_PATTERN`). Entry points: the Fleet
screen's Cars panel has a **Navigate** row (the least invasive existing place: the Fleet tile already
carries the car and the saved places), and every saved place's **Navigate** action opens the screen on
that place. A typed destination field is the hands path for `navigate`. Both reach the screen through
`LocalNavEntryPoints` (provided in `MainActivity` around the two Fleet destinations) rather than a
parameter threaded through Fleet's baselined signatures. States: not set up / token refused,
choosing, several-places-match, preview (3 routes, alternative dashed), guiding (banner, Then strip,
muted tag, sheet, four tiles, stops and routes panels), rerouting, a reroute that found nothing,
arrived, ended, route failure, offline. The assistant "pill" of the prototype is not drawn: the
shell's talk bar already sits under every screen.

**Map fixes from the spike.** Route line drawn after the style loads and redrawn on every style
reload; location puck on; camera put on the fix, follows while guiding, frames the route in preview
and overview, with a floating overview / recenter button; `MapView` built on an AppCompat-themed
context.

### Readings and calls to check (nothing here was asked of Kevin)

- **Ticket 03 "first match that is unambiguous wins" is read as: the first source that MATCHES
  decides.** Two saved places named like "gym" ask which, rather than silently falling through to a
  stranger's gym from search. One line (`DestinationResolver.resolve`) if the other reading was meant.
- **Hands path previews first** (routes and Start), where voice `navigate` starts at once.
- `navigate` / `preview` while a trip is guiding is refused ("Already navigating to X. End that trip
  first."); changing the trip is `addStop` / `setAvoid` / `takeAlternative`.
- A `via` along the route only honours the route for a CATEGORY search (the SDK marks along-route
  unsupported for forward text search); a `via` that is not a category degrades to proximity-biased.
- A contact with no address is "said in words" only when the phrase is the contact's whole name; a
  partial name match with no address ("Target Pharmacy") does not block a search for the business.

### Verification accounted for (L11)

- Unit: resolution order, ambiguity, per-source unreadable-vs-empty wording, the controller's result
  mapping against a fake SDK, the trip guard across arrival / End / screen-left-while-guiding / late
  responses, status "not navigating" and unknowns: **done** (`app/src/test/.../navigation/`).
- Screens rendered with Roborazzi at 384 x 832 (`NavScreenScreenshotTest`, baselines in
  `app/src/test/snapshots/nav-*.png`): **done**; the map is a stand-in there, so the route line,
  puck and camera are **deferred to the phone**.
- On the phone, **deferred to ticket 12 (a real drive) or an earlier walk**: route from a saved place;
  typed search; "nearest gas station"; alternatives; no tolls; add and drop a stop; overview and
  recenter; screen off mid-trip; leave the screen and come back mid-trip; arrival; End; the Mapbox
  dashboard's trip count after a few runs (no session left running); the route line, puck and camera
  follow on the real map; the `ThemeUtils` log noise gone; the along-route category search.

## Device-run fixes (2026-10-03)

A run on the Galaxy A25 found 14 defects. Tags: built (compiles), tested (a unit or screenshot test
exercises it), reasoned (inferred, owes the phone). Gates were run in the worktree; see the commit
messages `Fix nav logic defects...`, `Fix nav screen chrome defects...`, `Bring a running trip back...`.

| # | Defect | Status | What and where |
|---|---|---|---|
| 1 | Black map 25 s, no words | fixed (built, reasoned) | `NavMap` reports `MapStatus`; the screen shows "Loading the map" or "The map could not be reached. Check the connection; it will keep trying." and retries a failed style load 5 s after each failure. `NavFormat` now reads `net::ERR_NAME_NOT_RESOLVED` as network-shaped (tested). The route request still runs on its own; the choose sheet adds "The map could not be reached, so this may be slow." No controller timeout was added (it would fight `runTest` virtual time). |
| 2 | Scale bar overlaps UI | fixed (built, reasoned) | Scale bar and compass disabled in `NavMap` (the compass was the black circle under the overview button). Logo and attribution lifted above the sheet so they stay visible. |
| 3 | Camera ignores chrome | fixed (built, reasoned) | `NavContent` measures the top overlay and the sheet (`onSizeChanged`); `NavMap` pads follow, overview and preview-fit by the measured heights plus 16 dp, clamped to 60 percent of the map. Preview re-frames when the measurement changes. |
| 4 | Route labels wrong after a pick | fixed (tested) | `NavFormat.routeLabels` computes Fastest (least duration), Shortest (least distance) and No tolls (known none while another route has tolls) from each route's own numbers; several claims read "Fastest and shortest". `NavFormatTest`. |
| 5 | Add stop drops a picked alternative | fixed by saying so (tested) | The SDK cannot keep an alternative across a re-request. The controller tracks a picked alternative; a change that replaces it says so in the result and in `NavState.note` (shown on the preview and guiding sheets), once. |
| 6 | Keyboard hides Add stop | fixed (built, screenshot; reasoned on device) | The Add button is now beside its field, so it rides with the focused field in any window mode; the sheet scrolls and is wrapped in `imePadding()`. Go still works. |
| 7 | No "Turn cues muted" | fixed (screenshot) | The Then strip took the whole row and pushed the tag out. It is now its own pill under the banner (`nav-guiding-muted.png`). |
| 8 | Launcher return lands on home | fixed, approach below (built, reasoned) | LEGION is the home app, so a Home press goes to HOME by design (ADR 0050) and stays that way. The SDK's trip notification uses the package launch intent (MAIN + LAUNCHER, read from `MapboxTripNotification`); a launcher-category fresh start with a trip GUIDING now opens the nav screen (`TripResumeEffect`, nonce via `LocalTripResumeNonce`). Everywhere else a "Navigating to X, tap to return" bar sits under the status line while a trip runs (`TripReturnBar`). Least invasive: no redirect on Home, none on rotation. |
| 9 | Units mix | fixed (tested) | `UnitSystem` from the device locale (US, Liberia, Myanmar imperial). `NavFormat.distance` is feet then miles (or metres then km). SDK `DistanceFormatterOptions` and the route options' `voiceUnits` use the same choice, so ticket 11's cues inherit it. |
| 10 | "116 mi short" | fixed (tested) | "Trip ended with 116 mi to go. Nothing is navigating." |
| 11 | "nearest gas station" gave Zain Corporation, no category | partly fixed, SDK finding open (tested for the request, not the result) | Category phrases already went through `engine.search(categoryId, CategorySearchOptions)` with canonical ids (`gas_station`, `coffee`, `pharmacy`, `grocery`, `atm`, `bank`, `hospital`, `ev_charging_station`...); more plurals added and a test pins phrase to id. A hit's category and address now show on the preview and the choice card. The category path logging its outcome (never result names) tells a phone run whether the category search answered or the text fallback did. If a category search still returns a non-gas-station, that is an SDK/data finding, not LEGION's. Not confirmable here. |
| 12 | Address produced a POI ambiguity card | fixed (tested) | Candidates carry a `PlaceKind`; an address-shaped query whose top hit is an ADDRESS keeps only ADDRESS hits for the ambiguity test; two same-named addresses in different cities still ask. |
| 13 | Possible router leak | **CORRECTED 2026-10-03 (second run): it was real, see "Second device-run fixes" below.** The original reading, kept for its history: explained, not reproduced (tested on our side) | Our teardown creates the SDK once and destroys it once per trip on every way out (end, arrival, back-out of a preview, screen left, token change, failed start), now a test (`everyTripCreatesTheSdkOnceAndDestroysItOnce`), and `MapboxNavSdk.destroy` is idempotent. The warning counts routers the SDK builds inside each `MapboxNavigation` (more than one per instance) and is a notice that it shares a thread pool, not an error. Not proven the SDK frees the dedicated thread on destroy. `MapboxNavSdk` now logs live instances at create and destroy (tag `MapboxNavSdk`); zero between trips on the phone closes it. |
| 14 | Alternative not visibly dashed | fixed in preview, could not confirm on device | Preview alternatives are 6 px dashed with butt caps (round caps closed the gaps). The SDK's guiding route line cannot dash, so its alternatives are low-contrast grey instead. |

### Owed on the phone

First open on a slow or offline network (words, retry); scale bar and compass gone; preview fit, overview
and follow clear of the sheet and banner; pick an alternative, add a stop (the replaced sentence); the
stops panel with the keyboard up; mute; Home press mid-trip then the notification tap and the return bar;
units on the banner, sheet and the SDK's maneuver text; "nearest gas station" result and its category; a full
address; `adb logcat -s MapboxNavSdk` for live instances after several trips; alternatives in preview and
guidance.

## Second device-run fixes (2026-10-03)

Phone run 3 (Galaxy A25) found three defects. Branch `feat/mapbox-voice`, on top of ticket 11.

| # | Defect | Status | What changed |
|---|---|---|---|
| 1 | Native router leak across trips (defect 13 above was REAL) | fixed (tested on the controller seam; the real SDK is on-device only) | The log said created 1 / destroyed 0 per trip, yet the SDK's router counter climbed to "5 of 5 max" by trip 3. Creating and destroying a `MapboxNavigation` per trip leaks native routers, so the earlier "a notice, not an error" reading was wrong. Now ONE instance lives for the process: created lazily by `ensureSdk` on the first route request (which needs a token), rebuilt only when the token changes (the token is baked in; checked in `onTokenChanged`, `onTokenRefused` and, for a change nobody noticed because the screen was closed, in `ensureSdk` itself). `teardown()` no longer destroys: it stops the session, THEN clears routes (`MapboxNavSdk.clearRoutes` also clears the map feed, which `destroy` used to do). Billing is `startTripSession` / `stopTripSession` alone and the session runs only while GUIDING (`NavTripGuard`, unchanged rules, doc and a repeated-trips test added). Observers and the controller's listener are attached once per instance, so a second trip cannot double a cue. Tests: `theSdkIsCreatedOncePerTokenAndTheSessionIsBoundedByGuidingAcrossManyTrips`, `theSessionNeverRunsOutsideGuidingAndIsStoppedBeforeRoutesClear`, `aChangedTokenRebuildsTheSdkAndOnlyThen`, `aTokenChangeNoScreenNoticedStillRebuildsOnTheNextRequest`, `aSecondTripDoesNotSpeakEachCueTwice`, `theSessionShouldRunExactlyWhileGuidingAcrossRepeatedTrips`; the old create-once-destroy-once test is replaced. |
| 2 | Preview fit clipped the route origin under the sheet | fixed by cause (reasoned; the screenshot shows the sheet at about 68 percent of the map, over the 60 percent padding cap); not seen on the phone | The padding already used the measured sheet height, but the sheet was taller than the 60 percent cap the padding applies, so the cap clipped it. The sheet is now capped (defect 3), so the cap never bites. The fit also now includes the user's puck (device fix) with the route, and re-fits when the sheet height changes (already keyed on `insets`) or a fix first appears. |
| 3 | Stops panel covered the whole map | fixed (compiles; Roborazzi re-record; not seen on the phone) | `SheetColumn` is capped at half the screen height (about 60 percent of the map) and scrolls inside; the stops and routes panel bodies are capped at 30 percent of the screen and scroll inside, so the tiles and Start stay reachable. |

### Owed on the phone

`adb logcat -s MapboxNavSdk`: live instances stays 1 across 5+ trips and the SDK's router counter stays flat, no
"Too many OnboardRouter"; no doubled cues on trip 2; a pasted token mid-session rebuilds once; preview fit shows
the puck and the whole route above the sheet; stops and routes panels leave map visible; guiding sheet unchanged.

## Third device-run fixes (2026-10-03)

Phone run 4 (Galaxy A25), branch `feat/mapbox-voice`. Three small defects; no Hilt, tool-surface or cue-behaviour change.

| # | Defect | Status | What changed |
|---|---|---|---|
| 1 | After End, a new preview kept the follow zoom with the route under the sheet; the overview button did nothing | fixed by cause (decision tested; the SDK camera behaviour reasoned, owes the phone) | The SDK's `NavigationCamera` keeps the last mode it was given; nothing released it after a trip, so it stayed FOLLOWING and beat the preview's own fit, and the overview button only ever asked the SDK while GUIDING. New pure `navigation/NavCameraPlan.decide(phase, mode, routesShown)`: the SDK camera is engaged only while guiding (follow / overview), every other phase releases it (`requestNavigationCameraToIdle`), and a preview always fits route plus puck inside the measured insets whatever the mode, so overview works whenever routes are shown. `NavMap` acts on the decision. `NavCameraPlanTest`. |
| 2 | Stops panel stayed open after Start and showed on the guiding sheet | fixed (rule tested; wiring reasoned) | `NavViewModel` closes the open panel (and clears the stop field) on any phase change except a preview re-requesting itself (PREVIEW and REQUESTING), via `panelsCloseOn`. Covers Start, End, arrival and voice-driven changes. `PanelCloseTest`. |
| 3 | A muted cue drop left no trace | fixed (arbiter tested; the log line itself is on-device) | `CueEnvironment.dropped(why)` (default no-op) is called by `NavCueArbiter` when a cue is dropped because muted; `NavCueSpeaker` logs `turn cue dropped: muted` at info under tag `NavCueSpeaker`. Never the cue text. |

### Owed on the phone

Start a trip, End, request "1000 N Navarro St, Victoria, TX": preview fits route and puck above the sheet, and the
overview button re-fits; Start with the Stops panel open: the guiding sheet has no panel; mute, wait for a cue,
`adb logcat -s NavCueSpeaker` shows "turn cue dropped: muted".
