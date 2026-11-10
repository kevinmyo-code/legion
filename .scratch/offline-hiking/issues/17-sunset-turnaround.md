---
map: offline-hiking
ticket: 17
title: "Build: turnaround time against sunset, distance and ETA back"
type: build
status: open
status-detail: ""
blockers: ["15"]
blocked-by: ["[[15-track-recorder-and-backtrack]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: turnaround time against sunset, distance and ETA back

> **Partly covered by `feat/field-nav` (round 5, 2026-11-10), not merged.** Sunrise, sunset, civil
> and nautical twilight (`SunTimes` with an altitude parameter), moonrise, moonset and phase
> (`field/Almanac.kt`, checked against PyEphem) and a turnaround time = sunset less a margin the
> Field screen sets in 15-minute steps. Not built here: the pace-based return time (needs ticket
> 15's track) and the optional local alert at the margin.

**Release:** v1. **Size:** S.

## What

A line on the hike screen: "Sunset 6:12 PM. At your pace, back at the car by 5:40 PM." Amber when
the margin is under 30 minutes, red when the return time is after sunset.

## Rules

- Sunset from `navigation/MapLight.kt`'s sunrise equation (already offline, no library, accurate to
  minutes; traced). Civil dusk shown too: usable light ends about 25 minutes after sunset.
- Return time = now + distance back along the track / Kevin's moving pace so far on this hike (no
  pace until 10 minutes of movement; then it says "pace not known yet").
- It is an estimate and says so. No sound and no notification by default; one optional local
  alert at the margin Kevin sets (passes the compulsion test: anchored to the clock and sunset,
  actionable, no absence talk, one switch silences it).

## Verification

- Unit: return-time maths, margin colours, no-pace case, a hike that crosses midnight UTC.
- Compile, suite green. On the phone: owed to ticket 19.
