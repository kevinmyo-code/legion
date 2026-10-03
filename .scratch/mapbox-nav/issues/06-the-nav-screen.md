---
map: mapbox-nav
ticket: "06"
title: "The nav screen"
type: prototype
status: resolved
status-detail: "Kevin picked A, the bottom sheet"
blockers: ["04"]
blocked-by: ["[[04-voice-tool-surface]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# The nav screen

## Question

What does the in-app nav screen look like, and where does every voice tool from 04 live on it by
hand (ADR 0035)?

- 2-3 clickable HTML prototypes on a canvas, sized to the A25's 384dp (Kevin's standing preference).
- The phone's soft Material look (moved 2026-09-27), not mission-control.
- States: no token (says so, links Setup), choosing a destination, route preview with alternatives,
  guiding, rerouting, arrived, offline.
- How the assistant strip coexists with the map while guiding.

## Prototypes (2026-10-03)

Canvas: https://claude.ai/artifact/9hxxihwgzG3qGw1tFBycYt (private to Kevin). Sources in
`research/06-nav-screen-prototypes/`. A: turn banner plus bottom sheet. B: turn card, round button
rail, slim bottom bar. C: map over a large panel with labelled buttons.

## Answer (2026-10-03)

**Kevin picked A: turn banner plus bottom sheet.** Source of record:
`research/06-nav-screen-prototypes/nav-prototypes.html` (standalone; the `.dc.html` files are the
same design on the canvas).

- **Guiding:** a teal turn banner on top (arrow, distance to the turn, street), a "Then:" strip
  under it, a "Turn cues muted" strip on the right while muted. A bottom sheet with time left
  (large, green), distance, stop, arrival time, an End button, and four tiles: Add/Drop stop,
  No tolls, Routes, Mute/Unmute. The assistant sits as a pill above the sheet.
- **Preview:** sheet with the destination, up to three routes (name, via, time; selected one in
  teal) and Start. The alternative route is drawn dashed on the map.
- **Ended / arrived:** sheet says which, and "Nothing is navigating."
- **Not set up:** full screen, says so in words, button to Setup.
- Overview and recenter (in `change_trip`) are map controls, not tiles: a small floating button
  on the map, added at build. Every tile and button calls the same controller as its voice tool.
