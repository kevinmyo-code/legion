---
map: command-center
ticket: "08"
title: "Packages, flights, and the area, on glass"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Packages, flights, and the area, on glass

Survey: `track_package`, `flight_status`, `area_info`, `get_reported_crime_history` have no
renderer. Ticket 01 wants the first two as Home tiles; this ticket builds the components.

## Build

Composables (`ui/world/`), each callable from Home and each honest:

1. **Package card / flight card**: on-demand fetch through the SAME logic the voice tools use
   (refactor the tool bodies in LiveToolbox into shared functions if needed - the tool then calls
   the shared function; never copy). Shows the answer, the source mail line ("from your UPS mail,
   Tue"), the estimate label, and a fetched-at timestamp. In-memory only. Distinct failure states:
   no permission / no matching mail / could not reach Gmail / extraction failed.
2. **Area card**: current area name + AirNow air quality. AIRNOW_API_KEY is in BuildConfig and
   currently unconsumed - this builds its first consumer (`weather/` or `location/` following
   AreaInfo's conventions: keyless degrade-in-words, offline honest). A missing reading is NEVER
   rendered as clean air.

## Rules

- Read-through in full for mail-derived cards. No auto-poll; refresh is a user act.

## Verification

- Suite green both ways. Failure-state tests per card. On the phone: fetch a real package status
  and see its source named.
