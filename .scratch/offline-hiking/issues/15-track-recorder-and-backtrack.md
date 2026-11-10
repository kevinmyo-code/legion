---
map: offline-hiking
ticket: 15
title: "Build: breadcrumb track recorder, backtrack to start, battery saver"
type: build
status: open
status-detail: ""
blockers: ["08", "14"]
blocked-by: ["[[08-where-a-hike-lives]]", "[[14-hike-screen]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: breadcrumb track recorder, backtrack to start, battery saver

**Release:** v1. **Size:** M.

## What

Start / Pause / Stop recording on the hike screen. The track draws as a line; "Back to start"
shows the recorded line in reverse with distance remaining along it.

## Rules

- Recording runs in a foreground service of type `location` with an ongoing notification, so it
  survives the screen turning off (Android 14 FGS rules; the notification says "Recording hike").
- Points written to Room as they arrive (crash or dead battery loses seconds, not the hike).
  Exported as GPX on Stop (ticket 08: GPX is the stored form).
- Sampling: GPS every 5 s or 5 m by default. **Battery saver** mode: every 15 s / 15 m, short screen
  timeout on the hike screen, no 3D terrain, stated in the notification. (Outdoors has no dark
  preset, so the saver is about GPS rate and screen time, not a style.) Both sizes are tunable after
  the ticket 19 hike measures battery drain per hour.
- Filtering: drop fixes with accuracy worse than 30 m; no smoothing that invents points.
- Backtrack: distance remaining along the recorded track from the nearest track point to the
  start, and the straight-line bearing as a fallback. Not routing: it follows where he walked.
- Stats while recording: distance, moving time, elapsed time, ascent and descent. Ascent from GPS
  altitude with a hysteresis threshold, labelled an estimate (§4 rule 5). Not from the SDK's
  `getElevation`: it answers only for the visible map with terrain on (research 01 §4), so it goes
  blind when the screen is off.
- Nothing leaves the phone during the hike. Upload is ticket 24.

## Verification

- Unit: distance, ascent with hysteresis, nearest-point backtrack, accuracy filter, GPX export
  round-trip, all on fixture tracks (made-up coordinates).
- Compile, suite green.
- On the phone: a recording survives screen off for 30 minutes and an app kill (service restart).
  Battery drain per hour measured in both modes. Owed to ticket 19.
