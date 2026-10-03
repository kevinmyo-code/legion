---
map: mapbox-nav
ticket: "06"
title: "The nav screen"
type: prototype
status: open
status-detail: "Three prototypes up, waiting on Kevin's pick"
blockers: ["04"]
blocked-by: ["[[04-voice-tool-surface]]"]
open-blockers: 0
ready: true
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
