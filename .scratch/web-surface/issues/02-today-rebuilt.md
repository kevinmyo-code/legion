---
map: web-surface
ticket: "02"
title: "Today, rebuilt"
type: build
status: open
status-detail: ""
blockers: [" the day, then the horizon with shape"]
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Today, rebuilt

Charted 2026-09-12. See `.scratch/web-surface/map.md` for the evidence this rests on - every figure
there was read from the live engine in Kevin's own browser, not assumed.

## What this builds

Ticket 01's chosen horizon, made real. The day itself does not change much; what changes is that the
week stops being invisible.

- **The day**, as now: what is due today, today's events, tomorrow.
- **The horizon**, per 01's ruling. If A: a compact strip of day cells carrying counts, so nine
  deadlines on one day read differently from nine spread across nine.
- **Load stated in words.** "9 due tomorrow" is a fact the screen should say, not one the user
  derives by counting rows.
- **Group the cluster.** Nine rows all reading `11:59 PM` carry no information in their times. Group
  by course, or collapse to a count that expands.
- **Done tasks stay visible and look done.** One of tomorrow's nine is already submitted; hiding it
  loses the fact that it is handled.

## The trigger this names rather than pre-building

Today reads the whole table and filters client-side - 312 events, which is nothing. **The trigger for
a server-side day-range endpoint (`web-and-households` 11) is when `/api/changes` for
`events,checklists` exceeds roughly 5,000 rows or 1 MB**, whichever comes first. Below that, a report
endpoint is a second place for the day logic to live and disagree.

## Verification

- Against the live engine, not a fixture: the week strip must match what `/api/changes` actually
  holds on the day it is run.
- The Monday case explicitly - the day the current design goes blank. Set the clock or pick a day
  offset and confirm the screen still shows where the next cliff is.
