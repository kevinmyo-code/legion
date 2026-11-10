---
map: offline-hiking
ticket: 08
title: "Decision: where a hike lives among the seven aspects"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: where a hike lives among the seven aspects

## Question

A recorded hike is a GPS track (thousands of points), a summary (start, end, distance, ascent,
duration) and a few waypoints (car, trailhead, a spot he marked). The aspects are fixed at seven
(CLAUDE.md §1: fleet, ledger, pantry, notes, dates, places, body); an eighth is a ruling. Where does
a hike go, and where does its track go?

## Options

| Option | Fit | Problem |
|---|---|---|
| A. Places | Waypoints already fit `Place` (label, lat, lng, address); the car and trailhead are places. | A track is not a place, and a hike log is not a saved spot. Places would grow a time-series it was not built for. |
| **B. Body (training), with waypoints in places** | ADR 0077 names training as body. A hike is a session: distance, ascent, duration, an estimated energy figure. The Monday check-in already counts sessions. | Body's rows are household-shared on the ruling that only Kevin logs; a hike his wife records would need the owner column ADR 0077 names. The track geometry is bulky for a body table. |
| C. Notes | A hike as a note with a GPX attachment. Cheapest. | No structure: "how many miles did I hike this month" becomes text search. |
| D. An eighth aspect, outdoors | Clean home. | A ruling for one activity; trips and drives already live in fleet, so the pattern argues against a new aspect. |
| E. Phone only, no engine | Hikes stay on the phone (Room + GPX files). | Lost with the phone; no web view; Alfred online cannot answer "how far did we hike Saturday". |

## Recommendation

**B: a hike is a body training session; its waypoints are places; its track is a GPX file attached
to the session row.**

- Summary fields on the session: start and end time, distance, ascent and descent, moving time,
  the park's name as Kevin typed or picked it, and an energy figure labelled an estimate (§4 rule 5).
- The track is stored as a GPX file (the format every other tool reads), on the phone first and
  uploaded to the engine when signal returns. It is first-party: LEGION's own GPS, recorded because
  Kevin pressed Start (the ADR 0041 provenance line). Not covered by Mapbox's 2.10.1 (it is not a
  Directions result).
- Car and trailhead waypoints become ordinary places, so "take me back to the car" online uses the
  existing `navigate` path.
- Who it belongs to: the session row is shared like other body rows today. **If his wife will record
  hikes too**, the owner column comes first (ADR 0077's own condition). Ask.
- Never a nudge: body's "never a workout-due or you-haven't-trained nudge" (ADR 0077 amendment)
  covers hikes. No hiking streak.

## Kevin must also say

- Does his wife hike with it on her own phone? (Decides the owner column.)
- Keep every track forever, or delete raw points after N months and keep the summary?
