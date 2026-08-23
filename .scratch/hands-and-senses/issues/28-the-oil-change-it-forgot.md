---
map: hands-and-senses
ticket: "28"
title: "It has the oil change on file and still says it has no record"
type: bug
status: built
status-detail: "Fixed: last-done derives from service records; the form no longer nulls a date a record established. Owes the on-phone ask."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# It has the oil change on file and still says it has no record

Kevin, 2026-08-22: *"asking AI > when did i do the oil change on the jeep > says he has no record of
it"* and *"i already logged oil change on the jeep"*.

**He is right, and the data proves it.** Pulled from the phone's own database (v32, this session):

| Source | Mileage | Date |
|---|---|---|
| `service_records` id=2, Jeep, Oil Change, `deleted=0` | 227,374 | **2026-08-12** |
| `maintenance_items` anchor, Jeep, Oil Change | 227,483 | **NULL** |

The record exists with a real date. The assistant still answers "no record", so this is a READ
defect over intact data, not lost data.

## Two stacked causes, both traced

**1. The answer never looks at `service_records`.** `vehicle/MaintenanceAgent.kt:106-114` composes
"last done" purely from the anchor's `lastDoneMileage`/`lastDoneDate`, falling to `UNKNOWN` when
both are null. `service_records` - the table that holds the actual dated event, and that the
Service History screen renders - is not consulted anywhere in that path. So the app can show Kevin
the oil change on a screen while telling him by voice that it has no record of it.

**2. A mileage-only anchor write CLEARS the date.** `VehicleController.mergeBackfillAnchors`
(~:1700) sets `lastDoneDate = null` when a mileage is supplied without one, and vice versa. Its
doc's reasoning is sound in isolation - do not pair a fresh mileage with a stale date from a
DIFFERENT event - but the effect here is that a later mileage-only "mark done" wiped a date that a
real logged service had established. The anchor at 227,483 with no date is that write.

## Build

1. **The answer reads the richer source.** When the anchor has no date, fall back to the most
   recent non-deleted `service_records` row for that vehicle and service, and say so with the date
   it carries. Where the two disagree, prefer the anchor's mileage and the record's date only when
   they plausibly describe the same event, and **when they cannot be reconciled, say both rather
   than silently picking one** - "last logged at 227,374 mi on 12 Aug; the maintenance clock was
   later set to 227,483 mi" is honest; a merged fiction is not.
2. **Never render UNKNOWN while a record exists.** That specific sentence is the user-visible bug.
   A test must assert: given a service record and a dateless anchor, the composed answer names the
   date.
3. **Stop the date-clearing where it is wrong.** A mileage-only write must not null a date that
   came from a logged service record. Keep the anti-pairing rule for anchors the user is
   explicitly overriding (`neverDone`, or an explicit backfill), but a "mark done" that already has
   a dated record behind it should preserve or re-derive that date. **Decide which write path did
   this** (voice backfill vs the maintenance screen's DONE_AT) and fix at that site, with a test.
4. Check the same blind spot for other services and other vehicles - the Jeep's Brake Pads anchor
   is also dateless (and soft-deleted), so this is a class, not one row.

## Verification

- Suite green both ways, one run fresh.
- Tests: dateless anchor + existing record renders the date; mileage-only write preserves a
  record-derived date; unreconcilable pair states both facts.
- **On the phone, with Kevin's real data**: ask "when did I do the oil change on the jeep" and get
  12 Aug 2026 at 227,374 miles.
