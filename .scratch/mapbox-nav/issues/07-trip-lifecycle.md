---
map: mapbox-nav
ticket: "07"
title: "Trip lifecycle: screen off, backgrounded, billed"
type: decision
status: resolved
status-detail: "Kevin: keep guiding with the screen off"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
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

## Answer (2026-10-03)

**Kevin: screen off or app backgrounded mid-trip, guidance keeps going.**

- The SDK's own `NavigationNotificationService` (`foregroundServiceType="location"`) carries the
  trip; the spike proved it starts with guidance and is gone after Stop and after backing out
  (on-device, 2026-10-03). Its notification is the visible sign that a trip is running.
- Turn cues keep speaking through LEGION's speech path (ticket 05) with the screen off.
- **A trip ends only on arrival, End (voice or tile), or process death.** Leaving the nav screen
  while guiding does NOT end the trip any more (the spike ended it on back-out; ticket 10 changes
  that). Process death takes the in-process session with it, so no session outlives the app.
- **Voice start while backgrounded brings the nav screen forward** (ruled earlier). If Android
  refuses the launch, guidance still starts and the assistant says the map could not be shown.
- **No free drive, ever.** The 8 ms Free Drive blip at session start is inside Mapbox's 30 s grace
  (reasoned); check the dashboard trip count after test runs.

Owed on the A25 (ticket 10 and 12): cues with the screen off, a voice start while another app is in
front, and the dashboard trip count.
