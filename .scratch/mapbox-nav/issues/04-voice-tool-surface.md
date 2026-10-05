---
map: mapbox-nav
ticket: "04"
title: "The voice tool surface and its honesty contract"
type: decision
status: resolved
status-detail: "Kevin: four wide tools"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
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

## Answer (2026-10-03)

**Kevin picked four wide tools** (Live re-bills every declaration every turn):

```
navigate(destination, via?, avoid?, preview?)   start a trip, or preview routes without starting
change_trip(action, ...)                        add_stop | remove_stop | avoid | take_alternative
                                                | mute | unmute | overview | recenter
trip_status(ask)                                time_left | distance_left | arrival | next_turn
                                                | road | speed_limit | traffic
end_trip()
```

**`navigate` replaces `open_navigation`** in ticket 11 (ADR 0054). "Nearest X" and "along the
route" are `destination` / `via` phrasings resolved by ticket 03, not separate tools.

**The honesty contract** (§7 outcome verbs; every result is read back from SDK state AFTER the
call, never inferred from the call being made):

| Tool | Success only when | Says on failure / absence |
|---|---|---|
| `navigate` | a route is set AND the trip session is active (`preview` = routes returned, nothing started) | what was not found, or that no route came back; nothing is navigating |
| `change_trip` | the new route containing the change is set (stop present / absent, exclusion applied) | the trip is unchanged, still going to <destination> |
| `trip_status` | read-only; values from the latest route progress | **"not navigating"** with no trip, never zeros; a value the SDK does not have (speed limit on a road without one) is said as unknown |
| `end_trip` | the trip session is stopped | "nothing to end" when no trip was running |

- Mute and unmute act on turn cues only (ticket 05), never the assistant.
- Every action has a control on the nav screen calling the same controller (ADR 0035, ticket 06).
- A rebuilt route for a new stop starts a new billed trip (research 01). Not said to the user; it
  is well inside the free tier.
