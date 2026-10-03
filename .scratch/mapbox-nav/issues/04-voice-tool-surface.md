---
map: mapbox-nav
ticket: "04"
title: "The voice tool surface and its honesty contract"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# The voice tool surface and its honesty contract

## Question

Which tools, with what parameters, and what does each return when it did not happen?

Candidates: start a trip, preview routes / pick an alternative, add a stop, remove a stop, avoid
tolls / highways / ferries, trip status (time left, distance left, arrival time, next turn, current
road, speed limit), traffic on the route, search along the route, mute / unmute guidance, show
overview / recenter, end the trip.

- Fewer, wider tools vs many narrow ones (Live re-bills every declaration every turn).
- What `open_navigation` becomes.
- Every result derived from SDK state after the call, never from the call being made (§7).
- "Not navigating" is its own answer: trip status with no active trip says so, never zeros.
