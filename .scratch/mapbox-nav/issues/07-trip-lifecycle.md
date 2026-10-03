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
