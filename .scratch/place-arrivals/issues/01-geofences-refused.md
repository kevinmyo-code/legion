---
map: place-arrivals
ticket: "01"
title: "Why Google refuses LEGION's geofences, and saying so when it does"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Geofences are refused

## Evidence, A25, 2026-09-27

Logcat, repeated every time LEGION re-arms, as recently as 12:22:

```
W/Geofencer: registration not active, registration not permitted for registration:
  10358/com.kevin.legion/33de5e6f {na} (FINE) GeofenceRequest(29.782164,-95.532689+150.0m,
  eventsFilter=[INSIDE, OUTSIDE], initialEventsFilter=[INSIDE] ...
```

The same line appears for all three saved places (the three coordinates in the log). `dumpsys location`
shows `Geofence Manager: service: unregistered`.

**Already ruled out:** background location. `ACCESS_BACKGROUND_LOCATION` is `granted=true,
USER_SET` for user 0, and FINE and COARSE are allowed. Background location isn't the cause.

## Not yet checked, in rough likelihood order

1. **Google Location Accuracy** (Settings > Location > Location services > Google Location
   Accuracy) switched off. Geofencing depends on it and fails with GEOFENCE_NOT_AVAILABLE.
2. The `addGeofences` failure callback in `GeofenceManager`: does it log the ApiException status
   code at all? If the refusal is swallowed, that's also a section 1 problem (below).
3. Samsung battery restriction on LEGION ("sleeping apps"). Google's geofencer can refuse
   registrations for a restricted app.
4. Play services location consent for this app.

## The honesty half, and it binds whatever the cause is

A place-arrival reminder the user set, which will never fire, is the worst shape of this bug:
nothing on any surface says it's broken. CLAUDE.md section 1: unreadable and empty are different
sentences, and "armed" and "refused" are too. When `addGeofences` fails, the places screen and the
voice tool that sets an arrival reminder must say, in words, that arrival reminders can't be armed
right now and why, instead of accepting the reminder silently. The outcome-verb rule (section 7)
applies to `set_reminder` with a place trigger: it may not say the reminder is set if the geofence
behind it was refused.

## Verification

- Find the cause from the list above on the A25, and fix it or name the setting Kevin must change.
- `dumpsys location` shows LEGION's geofences registered; no "not permitted" in logcat after re-arm.
- A test that a refused `addGeofences` surfaces a failure the UI and tool render in words.
- On the phone: arrive at a saved place (or mock the location) and confirm the reminder fires.
