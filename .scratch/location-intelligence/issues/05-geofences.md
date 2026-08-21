---
map: location-intelligence
ticket: 5
title: "Geofences that actually fire"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-background-location]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Geofences that actually fire

## What to build

Replace `PlaceController`'s raw distance math on GPS polls with registered OS geofences (settled
decision 9). Event-driven, cheaper on battery than polling, and it works with the app closed -
**which is what makes place reminders start firing properly** instead of depending on a poll
happening to land near the place.

## What it changes, named rather than discovered

- **Existing `TaggedPlace` rows must be registered** on migration.
- **Geofences do not survive a reboot.** Re-register from `BootReceiver`, which already exists for
  the reminder alarms.
- **Android caps an app at 100 geofences.** Decision 9: register the **nearest N to Kevin**,
  re-registering as he moves. That re-evaluation is itself a location-triggered job - design it
  before writing the migration, not after.
- Needs [ticket 01](01-background-location.md)'s permission or it silently does nothing with the app
  closed, which is the whole point.

## Verification

- Suite green on the nearest-N selection.
- **On the phone:** a place reminder firing on arrival with the app closed, and surviving a reboot.
  That reboot case is the one most likely to be quietly broken.
