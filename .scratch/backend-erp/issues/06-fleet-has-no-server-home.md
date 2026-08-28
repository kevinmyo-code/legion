---
type: decision
status: resolved
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

## RULED 2026-08-26: the remaining tables

Kevin delegated these ("go with your recommendations"), so they are my calls made on his authority
and are open to reversal. The reasoning is written out so a reversal has something to argue with.

**The four diagnostic/observation tables SYNC: `code_events`, `code_clear_events`, `oil_analyses`,
`chassis_quirks`.** Ruling 10 keeps `obd_samples` local for a stated reason - ephemeral and
high-frequency - and none of these four is either. A stored DTC, the fact that codes were cleared on
a date, and an oil analysis are per-vehicle HISTORICAL FACTS: low-volume, individually meaningful,
and exactly the shape of a record the laptop surface would want. `chassis_quirks` is reference data
rather than observation, and syncs for a different reason: it is small, shared across devices, and
pointless to re-derive per phone.

**`vehicle_specs` and `build_entries` SYNC.** `vehicle_specs` is vehicle master data in the ERP
framing - it belongs beside `vehicles`, not in a second store. `build_entries` is user-authored
content, which is the clearest possible case for durability.

**`drive_reassignments` SYNCS, and it must land in the same wave as `drives`.** It is a correction
log over drives; splitting a fact from its corrections across two systems is the split-brain this
whole phase exists to avoid.

**`car_tasks` FOLDS INTO `events`, and does not get its own table.** Ruling 4 already decided todos
become Dates events, and a car task is a todo that happens to name a vehicle. A second todo table
would be exactly the duplication ruling 4 removed. **Consequence to honour:** `events` needs a way
to reference a vehicle, which it has no column for today. That is a small additive migration and it
should be done as part of the fleet cutover, not bolted on afterwards.

**Net effect on the schema:** fleet needs new server tables for `code_events`, `code_clear_events`,
`oil_analyses`, `chassis_quirks`, `vehicle_specs`, `build_entries` and `drive_reassignments`, plus a
vehicle reference on `events`. `drives` already exists. `obd_samples`, `monthly_recaps` and
`yearly_wrapped` deliberately get nothing - the first stays local, the other two are recomputed.

Fleet is now fully decided and unblocked. It is the largest remaining build in the arc.

## Superseded: what was open

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

## REVERSED 2026-08-28 (Kevin): `car_tasks` does NOT fold into `events`. The fold already existed.

The 2026-08-26 ruling above says: *"`car_tasks` FOLDS INTO `events`, and does not get its own table.
Ruling 4 already decided todos become Dates events, and a car task is a todo that happens to name a
vehicle. A second todo table would be exactly the duplication ruling 4 removed."*

**The reasoning was right and the premise was wrong. There was already no second todo table - because
car tasks had already been folded into Notes, years earlier, by a path this ticket did not know
about.**

Traced while building the wave:

1. `MIGRATION_9_10` copies `car_tasks.syncId` **verbatim** into `list_items.syncId`.
2. `EngineDataMigrationWave1.copyNotesIfNeeded` reuses that `syncId` directly as the engine record's
   `guid`.
3. `EventsReconcile` uploads that engine record to `public.events` as `kind = 'reminder'`.
4. `car_tasks` has had **no production writer** since the fold - the only readers left are
   `FleetReconcile` and a tombstone sweep.

So every surviving `car_tasks` row necessarily has a Notes sibling carrying the same guid, and the
wave was creating a SECOND server representation of a task Notes already owned. The duplication this
ruling set out to prevent is exactly what building it would have caused.

**It surfaced the way these things do - as a wrong-looking number.** The first real run reported 13
of 14 car tasks uploaded with one "only on this device". That row was on the server all along, under
`kind = 'reminder'`. Only one collided because only one had a date; undated note items never upload.
Give the other thirteen a date and each duplicates in turn.

**Kevin's call: drop the wave.** Not reconcile the two representations, not keep both - Notes already
owns these, and the `vehicle_id` column that would have justified a fleet-side copy is null on every
one of them, so a car task is not currently expressing anything a Notes item cannot.

**What that costs, stated plainly:** `20260828000100` (the third `kind`) was applied to the live
project and a wave ran against it, so 13 rows exist server-side that nothing will maintain.
`20260828000300` deletes them and narrows the constraint back. `20260828000100` is NOT deleted or
edited - it happened, and a migration history that erases its own mistakes is worse than one that
admits them.

**What survives the reversal, because it was right independently:** `EventsReconcile`'s two-way
`partition { kind == APPOINTMENT }` was putting every unrecognised kind into the REMINDER bucket by
default, which would have refilled a foreign row straight into the Notes store. Explicit per-kind
filters stay. That defect was found only because the car-task work went looking.
