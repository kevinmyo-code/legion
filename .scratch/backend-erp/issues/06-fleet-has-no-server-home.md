---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# Fleet has no server home for most of what it is

**Filed 2026-08-26, on reaching aspect 3 of the phase 4 cutover and finding it does not fit the
shape the other four do.** This is a BLOCKER for the fleet cutover and it needs Kevin's rulings,
not execution.

## What happened

Places and pantry cut over cleanly because each maps onto server tables that already exist. Fleet
does not. Ticket 05 phase 4 step 4 says "remove that aspect's tables from the `SyncEngine` registry
in the same commit", which silently assumes every table an aspect owns has somewhere to go.

**Fleet spans 13 tables still in the `SyncEngine` registry, plus two more outside it. The server has
three fleet tables.**

| Phone table | Server home | Status |
|---|---|---|
| `vehicles` | `public.vehicles` | maps |
| `service_records` (not in the registry) | `public.service_history` | maps |
| `maintenance_items` (not in the registry) | `public.maintenance_schedules` | maps |
| `obd_samples` | none, deliberately | ruling 10: OBD live state is phone-only, ephemeral and high-frequency. Settled. |
| `drives` | **none** | **contradicts ruling 10, see below** |
| `drive_reassignments` | none | undecided |
| `build_entries` | none | undecided |
| `car_tasks` | none | undecided |
| `chassis_quirks` | none | undecided |
| `code_events` | none | undecided |
| `code_clear_events` | none | undecided |
| `oil_analyses` | none | undecided |
| `vehicle_specs` | none | undecided |
| `monthly_recaps` | none | undecided, but see "derived" below |
| `yearly_wrapped` | none | undecided, but see "derived" below |

## The sharpest problem: `drives` contradicts a standing ruling

Ticket 01 ruling 10 lists what never leaves the device and says, in parentheses and in its own
words, that **"trips and maintenance still sync as records"**. So drives are explicitly NOT
phone-only residue. There is no `drives` table in `supabase/migrations/`.

This is not a small omission. `drives` is the fleet aspect's transaction table - the thing that
makes mileage, MPG and spend derivable at all - and `Drive.vehicleId` points at a vehicle. A
partial cutover that moves `vehicles` to Postgres while `drives` stays on the phone puts a parent
and its children in two different systems, which is the split-brain the reconcile-and-repoint shape
exists to avoid.

## RULED 2026-08-26 (Kevin): two of the four are settled

**1. `drives` sync.** Built the same day: `supabase/migrations/20260826000200_fleet_drives.sql`,
applied to the live project and verified (RLS on, one policy, one trigger - identical to `vehicles`
and `service_history`). **Keyed on `sync_id`, not `origin_guid`**, because drives are NOT engine
records - `FleetAspectSeeder` defines only Vehicle, ServiceHistory and MaintenanceSchedule, so
`Drive.syncId` was already the portable identity and there was nothing to invent.

Two details caught while writing it, both worth keeping: `provenance` defaults to `DETERMINISTIC`,
NOT `OBSERVED` - `OBSERVED` is a `kind` value on `service_history` and is not in the `provenance`
enum at all, so the first draft would have failed on apply. And `gallons` is nullable with no zero
default, carrying `Drive.gallons`' own rule forward: unknown fuel and no fuel are different facts
and must not collapse, or MPG lies.

**2. `monthly_recaps` and `yearly_wrapped` RECOMPUTE from drives.** No server tables for them. **The
arithmetic was deliberately NOT written server-side**, and that restraint is the point:
`vehicle/MonthlyRecapController.kt` and `vehicle/MpgTrust.kt` already implement it, and transcribing
it into SQL unchecked would create two implementations of one calculation with nothing proving they
agree - the exact hazard CLAUDE.md section 4 rule 1 and ticket 03 ruling 2 exist to prevent. Whether
the recap becomes a view, an RPC, or stays a phone-side computation over synced drives is its own
step, and if it goes server-side it needs a shared corpus the way the gate did.

## Still to be decided, and by whom

These are product and modelling calls, not execution:

1. **The four diagnostic/observation tables** (`code_events`, `code_clear_events`, `oil_analyses`,
   `chassis_quirks`) - do these follow drives to the server, or are they OBD residue that stays with
   `obd_samples`? They are not high-frequency the way samples are, which is the reason ruling 10
   gave for keeping samples local, so the ruling does not settle them either way.
2. **`vehicle_specs`, `build_entries`, `car_tasks`, `drive_reassignments`** - undecided, and each is
   small enough that the answer is probably "carry it", but none should be assumed.

## Why this was not caught earlier

Ticket 05 counted fleet as "62 records" and sequenced it third by size. That count came from the
ENGINE's record tables, which is the right measure of how much data moves but says nothing about how
many distinct TABLES need a server home. Places was 3 records in 1 table; fleet is 62 records across
15. **The sequencing metric and the schema-completeness question are different questions, and only
the first was asked.** Worth applying to the remaining aspects before assuming they fit.

## Not blocked on this

Notes+Dates (aspect 4) does not depend on any of it and is proceeding first. Ledger (aspect 5) has
its own open question, already recorded in ticket 05: whether its migration upload can route through
`commit_statement` or needs a key of its own.
