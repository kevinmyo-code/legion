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

---

## PROGRESS 2026-08-26

**Schema half built, NOT APPLIED (`54cdf5e`).** All seven tables plus `events.vehicle_id`. This
machine has no Supabase CLI, no linked project and no credentials, so unlike phases 1 and 2 nothing
here was verified against real Postgres. **Owed by Kevin:** apply both files; verify each table's
RLS by querying `pg_class`/`pg_policy` rather than trusting the editor's success panel; confirm the
natural keys and the new FK do not collide with seed data; and rule on whether a no-op
`drive_reassignment` needs a CHECK (left unconstrained, since a client retry of an
already-applied correction is legitimate).

Idempotency keys were confirmed against `SyncEngine`'s registry and the Room entities rather than
taken from this ticket's own paragraph. They matched.

**`events.vehicle_id` is `ON DELETE SET NULL`** where every fleet FK uses `RESTRICT`. Blocking a
vehicle delete over an unrelated todo reads wrong, and vehicles soft-delete so it fires close to
never. **A design call made on Kevin's delegated authority, not a ruling** - cheap to reverse now.

**Wave 1 built (`fa58865`): vehicles, service_history, drives.** Scoped to the three tables that ARE
applied. Two findings worth carrying:

**1. Fleet has TWO identity shapes inside one aspect, and this is correct.** `vehicles` and
`service_history` are engine records and key on `origin_guid`; `drives` never were engine records,
so `Drive.syncId` was already the portable identity. Every other aspect is uniform, so this is
documented in the code to stop it reading as a mistake.

**2. The legacy tables CANNOT serve as replicas for vehicles/service_history, which contradicts the
premise ticket 01 ruling 7 was working from.** Ruling 7's shortcut was "repoint the phone back to
the legacy typed tables that still exist". For fleet that does not hold: legacy `Vehicle` is keyed
on `obdMac`, and `FleetRecordBridge`'s own doc says obdMac is NOT recoverable from the engine guid
(a one-way `nameUUIDFromBytes` hash), so a server row cannot be mapped onto a legacy row's key. It
also carries local-only columns (persona, telemetry accumulators, archive state) a refill would have
to blank. `drives` was fine and reuses its own table.

So wave 1 is **upload-only** for those two, and the gap is visible in the TYPE rather than buried in
a comment: `VehicleReport`/`ServiceHistoryReport` have no `replicaCountAfter` field at all. Purpose-
built replica tables are being added next, the way `EventReplica` was for Notes+Dates.

**The vehicle reference has to be COMPOSED, not read.** `ServiceHistory` names its vehicle by the
engine record's Long id, `Drive` by the legacy obdMac string, the server by uuid. The reconcile
composes engine-id -> guid, obdMac -> guid, guid -> server uuid. A row whose parent cannot resolve
is skipped and named in `skippedUnresolvedVehicle`, never uploaded with a guessed parent - that
would put a service record on the wrong car.

**MaintenanceSchedule is out of scope** and always was: it has no `origin_guid` column in
`20260826000100`, so it was never part of this cutover. Worth an explicit decision later rather than
being silently absent.

**Still owed after the replicas land:** waves for the four newest tables (blocked on the migrations
being applied), the `car_tasks` fold into events, repointing production controllers, dropping each
table from the `SyncEngine` registry in the same commit its writes move, and the recap
view-vs-RPC-vs-phone decision (deliberately untouched - transcribing `MonthlyRecapController` and
`MpgTrust` into SQL unchecked is the two-implementations hazard ticket 03 ruling 2 exists to stop).

## APPLIED AND VERIFIED ON THE LIVE PROJECT 2026-08-27

All three outstanding migrations were applied to `HomeERPBackend` through the dashboard SQL editor
(driven via browser automation, at Kevin's explicit request): `20260826000600` (the seven fleet
tables), `20260826000700` (`events.vehicle_id`), and `20260827000100` (`events.structured_meta`).

**Verified by querying the catalog, not by trusting the editor's success panel** - the same posture
phase 2 used. All eight tables (`code_events`, `code_clear_events`, `oil_analyses`, `chassis_quirks`,
`vehicle_specs`, `build_entries`, `drive_reassignments`, `events`):

| check | result |
|---|---|
| `pg_class.relrowsecurity` | true, all 8 |
| `pg_policy` count | 1, all 8 |
| `has_table_privilege('anon', 'SELECT')` | **false**, all 8 |
| `has_table_privilege('authenticated', 'SELECT')` | true, all 8 |

Both layers demonstrated independently, as with the earlier RLS proof: `anon` is revoked at the
GRANT level before RLS is ever consulted, and the policy exists on top of that.

Columns on `public.events` confirmed present with the right types: `vehicle_id uuid` nullable,
`structured_meta jsonb` nullable, alongside the pre-existing `all_day boolean not null` and
`origin_guid text`.

**One dialog worth recording, because its wording is actively misleading.** The editor warned
"creates tables without enabling Row Level Security" and offered "Run without RLS" versus "Run and
enable RLS". The migration DOES enable RLS - through `private.apply_household_rls` inside an
`execute format` in a `do $$` block, which Supabase's static analyzer cannot see. **"Run without
RLS" was the correct choice**: it means "do not append Supabase's own RLS statements", and taking
the other option would have made the live schema diverge from the committed migration file.
Confirmed the macro's contents (`20260825000200_conventions.sql:124-131`) before choosing.

Migration history is still bypassed by the dashboard path, so a first CLI use needs
`supabase migration repair`, not a re-run - the files are idempotent. Same caveat as phase 2.
