---
map: location-intelligence
ticket: 1
title: "Background location, asked for honestly"
type: build
status: built
status-detail: "2026-08-21 - built; owes a run on the phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Background location, asked for honestly

## What to build

`ACCESS_BACKGROUND_LOCATION` (settled decision 11). **Everything else on this map depends on it** -
geofences do not fire with the app closed, and hazard checks only run while LEGION is open, which is
useless for a category about acting when Kevin is not looking.

Android splits this deliberately and it cannot be shortcut:

1. Foreground location must be granted **first**, in its own prompt.
2. Background is a **separate** request and, from API 30, the system dialog does not even offer it -
   the user is sent to Settings to choose "Allow all the time" by hand.

So the row must **explain before it asks**, in one line, why an assistant needs to know where he is
while closed - and it must handle the case where he grants foreground and refuses background, which
is a real and reasonable choice, not an error.

## Rules

- **A refusal degrades in words, never silently.** With foreground-only, hazard checks still work
  while the app is open and geofences do not fire at all - the settings row says exactly that
  rather than implying the feature is on.
- Follow `ui/SettingsRows.kt`'s existing shape (`DeckSwitch`, `LegionType.stamp`,
  `LocalLegionSemantics`) and `TodayScreen`'s "ask in context, not at startup" precedent.
- No new permission may be requested at first launch. This is asked for when he turns on hazard
  alerts or saves a place, not on the way in.

## Verification

- Suite green.
- **On the phone**, and all three matter: granting foreground only, then upgrading to background
  later, and the row's copy in each of the three states.

## Built - 2026-08-21

`location/BackgroundLocationAccess.kt` (three-state resolver, unit tested), `LocationAccessRow`, the
chained two-step request in `SettingsScreen`, and the manifest permission with its reasoning.

**The three states are the substance and they landed well.** Each is a different SENTENCE rather
than a different shade of the same one - "on", "partly on, and Allow all the time is what fixes
that", "off". Collapsing them to a boolean would have forced a lie in one direction or the other,
which is the ticket's own rule about degrading in words.

### Changed in review: an assumption that failed silently

The build used `context as? Activity` to reach `shouldShowRequestPermissionRationale`. That is
correct today - `LocalContext.current` under `MainActivity.setContent` IS the Activity, and
`LegionTheme`/`LegionShell` wrap composition rather than the Context - and the executing agent
tagged it `reasoned`, explicitly noting it had not traced the hosting.

**Its failure mode is silence.** A wrapped context makes the expression `null == false`, so the
"open app Settings" shortcut simply never appears and nothing says why - on the one screen a driver
lands on precisely because a permission is stuck. Replaced with a `tailrec findActivity()` that
walks the `ContextWrapper` chain, which cannot be wrong regardless of what wraps the composition
later. It is the first such helper in this codebase; there was no precedent to follow.

### Owed on the phone

- All three copy states, and the two-step upgrade: foreground granted, then background asked
  separately, then "Allow all the time" chosen in Settings.
- That the Settings redirect opens the right page.
- Below API 30 the background dialog is skipped entirely - `traced` in code, never run on a pre-Q
  device, and there is no pre-Q device here to run it on.
