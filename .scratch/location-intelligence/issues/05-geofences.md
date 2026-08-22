---
map: location-intelligence
ticket: 5
title: "Geofences that actually fire"
type: build
status: built
status-detail: "2026-08-21 - built; owes a run on the phone, especially the reboot case"
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

## Built - 2026-08-21

`GeofenceManager` (pure `nearest()`, cap 80 of Android's 100), `GeofenceBroadcastReceiver`,
`ArrivalController`, `BootReceiver` re-registration, and `play-services-location` added as a
dependency - it was not one before, since `play-services-auth` is Drive-only.

**The best thing in this build is an extraction nobody asked for.** Arrival handling was private
inside `AriaForegroundService`; it is now `ArrivalController.onArrived`, so the geofence receiver and
the GPS poll call ONE function instead of two copies that would have drifted. Any future arrival
signal calls that, never a third implementation.

### A fork the build surfaced rather than improvised, and its resolution

Both signals now converge on the same handler with no debounce - **and on a real arrival both will
usually fire, seconds apart**. That is a double announcement of the same reminder, and it was
flagged rather than papered over, which was the right call.

Resolved here with `claimAnnouncement(place, now)`: check-and-claim in ONE step, because the geofence
runs on a broadcast thread and the poll on the service scope, and a separate check-then-stamp would
let both pass before either stamped. Five tests, including that a second announcement re-arms the
window rather than leaving it open.

**Also fixed while in there:** the raise used `ruleId = "place_arrival"` for every place, so a
brush-off at the gym would have silenced arrivals at work and home for a day. Now per-place - the
same bug, and the same fix, as `reminder_due` earlier today. That is twice in one day a shared
ruleId has been the wrong default; **a rule id that is not per-subject is the exception, not the
norm.**

### Deliberately NOT done

The old GPS-poll path is **kept**. It is the fallback for anyone on foreground-only location, and
removing it in the same change would make place reminders worse for them. A later ticket can retire
it once geofences are proven on the phone - not before.

### Owed on the phone

- A reminder firing on arrival **with the app closed**. This is the whole point and cannot be faked.
- **Surviving a reboot.** The ticket named this as most likely to be quietly broken, and
  `BootReceiver`'s call will usually no-op (no GPS fix in the first milliseconds after boot) - the
  real re-arm comes moments later from the first poll.
- Whether 20-second re-registration granularity is fine enough near a 150m radius at driving speed.
  `reasoned`, never measured.
