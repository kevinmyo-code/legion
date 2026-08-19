---
map: drive-test-2026-08-18
ticket: 03
title: "The assistant said it opened Maps. There is no map feature at all."
type: task
status: open
status-detail: "Built on feat/navigation 2026-08-19; every on-device check still open."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The assistant said it opened Maps. There is no map feature at all.

## Question

Kevin, on a real drive, 2026-08-18: *"nav > google map doesnt work. ai doesnt open map. says hes
opening it but it doesnt."*

**This is not an intent bug, a resolution bug, or a permissions bug.** There is no navigation
capability anywhere in the app.

### What was checked, traced 2026-08-18

| Claim | Result |
|---|---|
| A tool opens a map | **No.** All **89** tools in `service/LiveToolbox.kt` were enumerated. None opens a map, starts navigation, or takes a destination. |
| Some non-tool path launches navigation | **No.** Zero hits for `google.navigation` or `geo:` anywhere under `app/src/main/java/com/kevin/legion/`. The only `ACTION_VIEW` launches are `media/SpotifyWebApi.kt:143` (the OAuth browser hop) and MainActivity's own redirect handling. |
| The manifest can see Maps | **No.** `AndroidManifest.xml`'s `<queries>` block (`:69-75`) declares exactly two things: `com.spotify.music`, and an https VIEW intent for browsers. **No Maps package.** |
| The prompt mentions navigation | **No.** `ai/AriaBrain.kt`'s `sharedInstructions` (`:71-...`) never mentions navigation, maps, directions, or destinations. Zero case-insensitive hits for `navigat` or `maps` in that file. |

**So with no tool to call, Gemini answered in free text and invented the compliance.** That is the
predictable outcome, not an anomaly. The model was asked to do something, had nothing to call, and
produced a plausible sentence. See [ticket 04](04-what-the-assistant-says-when-it-cannot.md) for the
general case.

### This is a gap, not a decision against navigation

The codebase already **anticipates Maps running**. `service/GeminiLiveSession.kt:1156` cancels the
device's own speaker output specifically so that turn-by-turn guidance does not bleed into the mic;
its comment names "Maps turn-by-turn guidance" outright, and `:1159-1160` explains that without it
"once navigation is running its continuous voice guidance bleeds into every captured turn".

Somebody built the audio accommodation for navigation and the navigation never landed.

## Scope of the build

An `open_navigation` tool. Minimum honest version:

1. **The tool.** Takes a destination string. Fires `google.navigation:q=<destination>` for
   turn-by-turn, or `geo:0,0?q=<destination>` for a plain map pin, via `Intent.ACTION_VIEW`.
   Decide which of the two is the default and whether the tool exposes the choice.
2. **`FLAG_ACTIVITY_NEW_TASK` is mandatory.** The launch originates from
   `AriaForegroundService`, which has **no Activity context**. Without the flag the launch throws.
3. **A `<queries>` entry, without which this silently no-ops.** Add
   `<package android:name="com.google.android.apps.maps" />` to the existing block at
   `AndroidManifest.xml:69-75`. On API 30+ package visibility is opt-in, and without the
   declaration `resolveActivity` returns **null** and the launch does nothing. This is the exact
   failure the Spotify comment in that same block already documents having been bitten by.
4. **An honest failure string when Maps is absent.** Not a silent return, not a generic error - the
   driver is told, in words, that the map app could not be found.
5. **The tool result must reflect whether `startActivity` actually ran.** This is the load-bearing
   requirement and the reason the ticket exists. A tool that returns success unconditionally
   reproduces the original bug behind a tool call instead of in front of one.

Also update `sharedInstructions` so the model knows the capability exists, per the pattern every
other tool family in that prompt already follows.

## Verification

- [ ] Ask for navigation to a named place on-device and confirm Google Maps actually opens with the
      destination. `on-device`.
- [ ] Confirm the launch works **from the foreground service**, not just from an Activity - this is
      what `FLAG_ACTIVITY_NEW_TASK` is for and the failure mode is a throw, not a no-op.
      `on-device`.
- [ ] Remove or disable the `<queries>` entry and confirm the failure is a **spoken honest failure**,
      not a silent success. If it silently succeeds, requirement 5 is not met.
- [ ] Confirm the tool result reports failure when `startActivity` did not run, and that the model
      then tells the driver so rather than claiming it worked.
- [ ] Confirm `GeminiLiveSession.kt:1156`'s speaker cancellation behaves as intended once navigation
      is genuinely running - it has never been exercised against real turn-by-turn audio.
      `on-device`.

## Built, 2026-08-19 - on `feat/navigation`, nothing verified on a phone

- **`location/NavigationController.kt`** (new). `launch(context, destination, mode)` returns an
  `Outcome`: `Launched`, `LaunchedAsMapPin`, `NoMapApp`, `BlankDestination`, `Failed(reason)`.
  `FLAG_ACTIVITY_NEW_TASK` is set unconditionally (requirement 2). `resolveActivity` null - the
  package-visibility failure as much as a genuinely missing app - returns `NoMapApp` and is spoken,
  never swallowed (requirement 4).
- **Requirement 5 holds mechanically.** `Outcome.Launched` is only ever returned from inside the
  `try` after `startActivity` returns; `succeeded()` is true for nothing else but a real launch and
  the pin fallback, and `open_navigation`'s `success` is that value. Confirmed by review, `traced`.
- **The pin fallback is a deliberate addition to the ticket's minimum** (Kevin, 2026-08-19): when
  turn-by-turn cannot be served, `geo:` is tried, and if it opens, the driver is told in words that
  directions did NOT start and they must start them. A lesser real thing, honestly labelled, rather
  than silence.
- **`AndroidManifest.xml`**: `com.google.android.apps.maps` plus `<intent>` queries for the
  `google.navigation` and `geo` schemes, so a non-Google map app can serve the pin (requirement 3).
- **`ai/AriaBrain.kt`**: a navigation section that names the tool and forbids claiming a map opened
  unless the call came back successful.
- **`MidnightEvents.navigationLaunch(mode, outcome)`** - mode and outcome only, never the
  destination.
- **9 unit tests** in `NavigationControllerTest` (URI shape, `%20` not `+`, UTF-8 escapes, the
  outcome-to-honesty mapping). Full suite 1641 tests, 0 failures. `tested`.

**Every box under Verification above is still unticked.** No phone was attached to the laptop this
was built on. Nothing here is `on-device`.
