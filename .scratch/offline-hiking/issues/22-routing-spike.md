---
map: offline-hiking
ticket: 22
title: "Task: spike, offline trail routing on the A25 (Mapbox walking vs BRouter)"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Task: spike, offline trail routing on the A25 (Mapbox walking vs BRouter)

**Release:** before v2 routing; can run any time, on a throwaway branch. **Size:** S (one session plus a walk).

## What

Answer research 02's open question with the phone, not the docs: does Mapbox offline `walking`
route along state-park trails, and how does it compare with BRouter's `hiking-mountain`?

## Steps

1. Throwaway branch, debug build. Download one park area with routing tiles:
   `getTilesetDescriptorFactory().build(tilesProfile = "walking")` into the shared TileStore.
2. Airplane mode. Request 5 routes between trail points with `RouteOptions` profile `walking`.
   Record: `RouterOrigin`, success or failure, whether the line follows the drawn trails, length.
3. Install the BRouter app (F-Droid), download the matching `rd5` segment, call its AIDL service
   (`getTrackFromParams`, profile `hiking-mountain`) for the same 5 pairs. Record the same.
4. Billing check: whether downloading routing tiles outside a trip session shows as a billed
   "Route tiles" request on the Mapbox account page (research 02: not included outside a session).
5. Write the table to `.legion/reports/` (real coordinates stay out of the repo); put only the
   pass/fail summary on this ticket.

## Done when

Ticket 06 can be ruled from measured results.
