---
map: one-today
ticket: "05"
title: "MAINTENANCE 0 DUE is computed against nothing"
type: build
status: built
status-detail: "Premise corrected 2026-09-01 (the audit read a deliberately-null column). The two real defects fixed in 69ebf7e: the service-history join now folds case and whitespace, and the Supabase never_done default drops to false to match Room. NOT yet run on hardware."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# MAINTENANCE 0 DUE is computed against nothing

Found in the 2026-08-30 audit, from the real device snapshot.

`maintenance_items`: **54 rows. 48 carry a mile interval, 35 carry a month interval, and ZERO carry a
`lastDoneDate`.** None is flagged `neverDone` either.

`VehicleController.isDue` is dual-axis - miles **or** months. With no `lastDoneDate` on any row, **the
month axis has no anchor to measure from on any item.** So the 35 time-based intervals contribute
nothing, and FLEET's "0 DUE" is the mileage axis alone.

**That number may not mean what it says.** It reads as "nothing needs doing" and actually means
"nothing has tripped the one axis that still works."

## CORRECTED 2026-09-01: the premise above is stale, and the audit read the wrong table

The original text is kept verbatim because the reasoning that produced it is worth keeping visible -
it was a correct inference from a query against the wrong columns. Traced against the code:

**1. `lastDoneMileage` and `lastDoneDate` on `maintenance_items` are deliberately always NULL.**
Every write path nulls them explicitly - `FleetEngineStore.upsertNewItem:898`,
`FleetEngineStore.insertIgnore:913`, `PopulateWrites.kt:56`,
`EngineFleetServiceHistoryRetirementCopy.kt:147-148`. The columns are dead storage on the schedule
row. The Supabase schema does not even have them, and says why in its own comment
(`supabase/migrations/20260825000500_aspect_places_fleet.sql`): *"Deliberately carries NO
last_done_mileage or last_done_date... 'Is this due' is computed by reading the latest matching
service_history row against these intervals."*

So "zero rows carry a `lastDoneDate`" is the design working, not a data gap.

**2. The anchor is derived at read time from `service_records`**, via
`FleetEngineStore.toItemsLegacy` -> `FleetRecordBridge.projectAnchorLegacy:246-249`, which takes the
most-recent record for that service and reads both axes off it.

**3. The disclosure this ticket asked for already exists.** `VehicleController.isUnknown:1826`
separates "no anchor on either axis" from "not due", `buildDueRows` filters unknowns out of the
due rows, and `buildFleetTile` (`ui/TodayGapResolvers.kt:488-499`) reserves `"OK"` for
`overdue == 0 && unknown == 0`, printing `"$overdueCount due - $unknownCount unknown"` otherwise.
A bare confident zero is already unreachable when anything is unknown.

## What is actually wrong, and what this ticket now builds

The ticket changes `type` from `decision` to `build`. Nothing above needs deciding; two real defects
came out of the trace.

**A. The service-history join is exact string equality, with no normalization.**
`toItemsLegacy` groups `service_records` by `serviceName` and matches on `(vehicleId, serviceName)`.
No trim, no case-fold, at either end. `"Oil Change"` and `"Oil change"` are different services, and
the failure is silent: the item simply reads as *unknown* forever, which the tile then reports
honestly and uselessly. With 6 service records against 54 schedule items, a single casing
difference is the whole difference between an anchored item and an unanchored one.

Fix: normalize on both sides of the join (trim + case-fold for MATCHING only - the stored display
string keeps its original casing). Add a test with a casing and whitespace mismatch.

**B. `never_done` defaults disagree between Room and Supabase.**
Room's `MaintenanceItem.neverDone` defaults `false`; `public.maintenance_schedules.never_done`
defaults `true`. `isDue`'s first line is `if (item.neverDone) return true` - so the default decides
whether an un-anchored item reads as *always due* or as *unknown*. A row that round-trips through
the server could come back meaning the opposite of what it meant locally.

Fix: determine which default is correct, make both schemas agree, and cover the round trip with a
test. **This is a data-integrity question, not a cosmetic one** - it changes what the app tells
Kevin is due on his cars.

## Also worth noting

`maintenance_items`' 54 rows are split across FOUR `vehicleId`s, including **`default` (16 rows)** -
one of the two year-0 placeholder vehicles that the fleet cutover refuses to export and that halted
the OBD upload. Sixteen maintenance items hang off a car that does not really exist. Unchanged by
the correction above, and still owed.

## The lesson, for `library/lessons.md`

**An audit that queries a column proves nothing about the value the code computes.** The snapshot
was accurate, the query was right, the inference was reasonable, and the conclusion was wrong -
because the field it read had been architecturally retired in favour of a read-time join, and
nothing in the table's shape said so. Where a value is derived, audit the derivation, not the
column it used to live in.
