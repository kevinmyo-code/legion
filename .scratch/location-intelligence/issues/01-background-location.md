---
map: location-intelligence
ticket: 1
title: "Background location, asked for honestly"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
