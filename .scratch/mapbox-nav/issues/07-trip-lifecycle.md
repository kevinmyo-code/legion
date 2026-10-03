---
map: mapbox-nav
ticket: "07"
title: "Trip lifecycle: screen off, backgrounded, billed"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Trip lifecycle: screen off, backgrounded, billed

## Question

What runs when, and what stops it?

- Screen off or app backgrounded mid-trip: does guidance continue? Whose foreground service carries
  it: the SDK's own, or `AriaForegroundService`?
- Starting a trip by voice while LEGION is in the background: does the nav screen come forward?
  (The overlay grant may allow it; untested.)
- Billing guard: a trip session starts only on a trip and stops on arrival, cancel, or app death.
  No free-drive in the background. How is "no orphaned session" tested?
- Arrival: detected by the SDK, announced how, and does the session end itself?

## Ruled 2026-10-03 (Kevin), the rest still open

**A trip started by voice while LEGION is in the background brings the nav screen to the front.**
Relies on the overlay grant LEGION already holds; untested, and proving it on the A25 is part of this
ticket. If Android refuses the launch, guidance still starts and the assistant says the map could not
be brought up, in words.
