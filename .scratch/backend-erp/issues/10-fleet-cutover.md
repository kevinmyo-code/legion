---
type: build
status: open
blocked_by: []
map: backend-erp
---

# Fleet cutover

**The build ticket ticket 06 leaves behind.** Every modelling call is now made; this is execution.
It is the largest remaining build in the arc.

## Server schema still to create

`drives` exists already (`20260826000200`, applied). Still needed:

| Table | Notes |
|---|---|
| `code_events` | stored DTCs |
| `code_clear_events` | when codes were cleared |
| `oil_analyses` | |
| `chassis_quirks` | reference data, small |
| `vehicle_specs` | master data, belongs beside `vehicles` |
| `build_entries` | user-authored; note `photoPath` was dropped at the port, so text only |
| `drive_reassignments` | **same wave as `drives`** - a fact and its corrections must not split |

Plus **a vehicle reference on `public.events`**, because ruling 06 folds `car_tasks` into events and
events has no vehicle column today.

**Deliberately no table:** `obd_samples` (phone-only, ruling 10), `monthly_recaps` and
`yearly_wrapped` (recomputed from `drives`).

## Idempotency key per table

Check each one rather than assuming. `drives` uses `sync_id` because `Drive.syncId` already existed;
several of these carry a `syncId` too (the `SyncEngine` registry lists `code_events`,
`code_clear_events`, `oil_analyses`, `build_entries`, `drive_reassignments` as `hasSyncId = true`),
so they likely follow the same pattern rather than needing `origin_guid`. `vehicle_specs` and
`chassis_quirks` are registered with natural keys (`vehicleId`, `quirkId`). **Confirm from the code,
not from this paragraph.**

## The order that matters

`vehicles` first, then everything that references it. `drives` and `drive_reassignments` together.
`car_tasks` needs the events vehicle column before it can move.

## Recaps

Ruling 06 says recompute from `drives`. **The arithmetic is deliberately not yet written anywhere
server-side.** `vehicle/MonthlyRecapController.kt` and `vehicle/MpgTrust.kt` are the existing
implementation. If it moves server-side it needs a shared corpus proving the two agree, exactly as
ticket 03 ruling 2 required for the gate. Deciding view vs RPC vs stay-on-phone is part of this
ticket.

## Done means

The diff is clean per table, writes land server-side, the phone renders from the replica, and every
fleet table is out of the `SyncEngine` registry. Same bar as places, pantry and notes+dates.
