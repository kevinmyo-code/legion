---
type: build
status: open
blocked_by: [14-a-vehicle-row-is-co-owned]
map: backend-erp
---

# The fleet cutover, for real this time

**The build ticket 14's reversal leaves behind.** Fleet stops being a one-way projection: OBD,
drives, codes, maintenance and vehicle state write to Supabase, and the PC can read and write them.

## What already exists and works

Ticket 10 built and ran the whole upload half on real hardware 2026-08-28. Eight of ten tables agree
exactly between screen, device and server: 3 vehicles, 4 service history, 17 drives, 59 code events,
2 code-clear events, 3 vehicle specs, 1 build entry. The server schema is applied and RLS-verified.
The `skippedUnexportable` pre-check reads `public.vehicles`' own DDL rather than patching one
constraint.

**So this is not a rebuild. It is turning a proven one-way export into a two-way path.**

## The three things the projection ruling let it dodge

1. **A live write primitive.** `FleetBackend` has only insert-only `uploadMigrated*` methods built
   for a one-time replay, guarded by an `origin_guid` existence check. A cutover needs upsert.
2. **An identity decision.** `vehicles` has no natural key: `20260826000100` explicitly rejected a
   unique `vehicles.name` as a product decision, and that same migration calls `origin_guid`
   "migration PROVENANCE, not identity". Reusing it as the live key is a decision to take
   deliberately, not a detail to slide past. **Today's `events` lesson applies directly**: a table
   with two unique keys and a guard that knows one of them fails at the worst moment, and
   `origin_guid` is not stable across re-imports for Google events. Check whether fleet has the same
   hazard before choosing.
3. **The co-owned row.** Ticket 14's option 1: a local sidecar for `personaPrompt`, `voiceName`,
   `personaTraits`, `archived`, `onboarded`, `lastOdometerPromptAt`, `tripMilesSinceBaseline`,
   composed on read. These stay phone-only per ruling 10 and must not reach the server.

## Owed alongside

- **`SyncEngine` registry drops per table**, in the same commit each table's writes move (ruling
  05). Fleet was exempted while it was a projection; it is not exempt now.
- **`obd_samples` is NOT in scope** unless ticket 14's open question is answered otherwise.
  Recommendation there: raw samples stay phone-only, drives/codes/maintenance/specs go.
- **The recap decision stands** - ticket 10 ruled recaps stay on the phone, and a two-way fleet does
  not change the reasoning: moving them owes a shared corpus, and `drives` is on the server either
  way so the inputs are already durable.

## Done means

Writes land server-side on the ordinary path, not on a button. The phone composes its reads from the
replica plus the sidecar. Every fleet table is out of the `SyncEngine` registry. The diff is clean
per table. Same bar the other aspects were held to - the bar ticket 14 amended away and this ticket
restores.

## RULED 2026-08-29: identity is the server's uuid, carried as `serverId`. Not `origin_guid`.

Delegated call, open to reversal. **The answer turned out to already exist in the codebase**, which
is the best kind.

`VehicleReplica` already carries BOTH `serverId` and `originGuid`. So does `Event`, and its doc
comment states the rule outright: *"[id] is a LOCAL surrogate, never the server row's identity -
[serverId] is that."* Fleet does not need a new identity scheme; it needs to use the one every other
cut-over aspect already uses.

- **`serverId` (the server's `id` uuid) is canonical.** Upsert by it when the phone has it; insert
  when it does not, and store the uuid the insert returns.
- **`origin_guid` keeps the meaning `20260826000100` gave it** - migration provenance, not identity.
  It stays useful for exactly what it was built for: making the one-time replay idempotent.
- **`obdMac` is not a candidate.** It is the phone's own key, it is a MAC address, and a car can
  change dongles. It stays local.

**The trap this must not walk into, already documented and already paid for once.** `Event`'s doc
warns that `OnConflictStrategy.REPLACE` keyed on a unique `serverId` would DELETE-then-REINSERT,
minting a new local `id`. On Notes that id is an `AlarmManager` request code and a soft foreign key
from three tables, and `b17bc88` was the incident where every reconcile silently reminted every one
of them. **Fleet has the same shape**: `Vehicle.obdMac` is referenced by drives, service records,
code events and specs. Read by `serverId` first and reuse the existing row's id, exactly as the
events path does.

**Why this also settles the co-owned row.** With `serverId` as the join key, the phone-only columns
(`personaPrompt`, `voiceName`, `personaTraits`, `archived`, `onboarded`, `lastOdometerPromptAt`,
`tripMilesSinceBaseline`) live in a local sidecar keyed on the same `serverId`, and a configured read
composes replica + sidecar. Ticket 14's option 1, with the join key already chosen.

## Step 1b done, and the `service_history` identity blocker dissolves

**Part A (ticket 27's correction) landed 2026-08-29.** The sidecar is down to its three genuinely
per-device columns; `archived` moved to `public.vehicles`
(`20260829000200_vehicles_archived.sql`, UNAPPLIED); the three vestigial persona columns stay in the
legacy `Vehicle` table, unread, with a doc comment saying what killed them. Room v50 -> v51.

### The `service_history` blocker, and why it is not one

The build agent stopped before `service_history` on a real finding: `public.service_history` has no
`sync_id`, its only write path is the one-time `uploadMigratedServiceHistory` keyed on `origin_guid`,
and four rows are already on the server that way. It framed the choice as *add a nullable `sync_id`
and accept those four can never be matched*, or *backfill `sync_id` by looking up `origin_guid`*.

**Neither is needed. `ServiceHistoryReplica` already carries `serverId`** - exactly like
`VehicleReplica` - so this table falls under the identity ruling already made above: **the server's
`id` uuid is the identity.** No new column, no backfill.

The four migrated rows are not a problem either. A first configured read calls
`fetchActiveServiceHistory`, gets those rows with their real uuids, and records them into the
replica. From then on `upsert` matches on `serverId` and cannot duplicate them. That is the same
"fetch first, record the uuid" shape vehicles uses.

**Why the agent reached for `sync_id`:** eight of the nine remaining tables genuinely key on it, so
it is the local pattern. `service_history` and `vehicles` are the two that do not, and
`FleetBackend`'s own doc already records that fleet carries two identity shapes inside one aspect
deliberately. **Confirm per table, as this ticket says - and the answer for these two is `serverId`.**

### What is genuinely left, sized honestly

The other eight tables have their server-side upsert primitives BUILT already (`upsertX`/`RemoteX`/
`XUpload` in `FleetBackend`, keyed on `sync_id` or a natural key). What they lack is the Room half:
no replica entities beside `VehicleReplica`/`ServiceHistoryReplica`, and **no facade** - twelve files
write those legacy DAOs directly (`FleetDigestBuilder`, `CarAspectSummaries`, `DrivingModeScreen`,
`FleetScreen`, `CarToolbelt`, `DailyDriveLogController`, `DtcClearController`,
`MonthlyRecapController`, `TelemetryRecorder`, `VehicleController`, `VehicleSpecController`,
`FleetReconcile`), where vehicles and service history funnel through `FleetEngineStore`.

So each remaining table is a replica entity + a Room migration + a facade seam + rewiring its
callers. **That is the real size of it, and it is several sessions, not one.** Sequence:
`service_history` -> `drives` with `drive_reassignments` -> the diagnostics trio -> specs, build
entries, quirks.

## Step 2 done 2026-08-29, and it is a PARTIAL cutover on purpose

`service_history` **writes** are cut over. **Reads still serve `service_records` unconditionally**,
configured or not. That asymmetry is deliberate, and it is not a shortcut.

### Why reads did not move, and why it is the right call

`composeVehicle` works for vehicles because **`Vehicle` has no numeric id at all** - everything keys
on `obdMac`. `ServiceRecord.id` is different: it is a load-bearing local surrogate.
`VehicleController.editServiceRecordDirect`/`deleteServiceRecordDirect` address a row by it, and
`MaintenanceSchedule`'s anchor derivation groups by those rows.

`ServiceHistoryReplica` is refilled **wholesale** by `FleetReconcile` - wipe and refill. Serving
reads from it would hand callers ids that change under them on every reconcile. **That is `b17bc88`
exactly**: the incident where a wholesale replica refresh reminted every local id, and those ids were
alarm request codes and soft foreign keys.

So: full cutover on the write side, legacy on the read side, and `FleetEngineStore`'s class doc
carries the reasoning. A safe read-side merge - materialising a replica-only row into
`service_records` with a fresh local id - is real follow-up work and is **ticket 28**.

### Two findings from the build, both worth keeping

**A live bug, found while wiring and fixed with a test.**
`writeAssertedAnchorLegacy`'s `REPLACE` rebuilt the whole row from scratch, which would have silently
wiped `serverId` on **every anchor edit** - breaking the identity the cutover had just established,
invisibly, on the path most likely to be used.

**The `SyncEngine` comment was a lie and is corrected.** `service_records`/`maintenance_items` were
already absent from the registry - retired at cutover 4, before ticket 16 repointed writes back onto
those tables - and the surviving comment claimed `MirrorSync` was the live cross-device path, which
ticket 16 had already falsified. **So since cutover 4, service records have had NO cross-device
channel of any kind.** This step restores one rather than removing one, which is the opposite of
ticket 27's finding and worth noting: the per-drop check found a channel that had been silently
missing for days.

### Identity confirmed, not assumed

`fetchActiveServiceHistory` really does return the server's own `id` as `serverId` - read, not
inferred. No `sync_id`, no backfill. `ServiceRecord` gains a nullable `serverId` co-located on the
row rather than in a sidecar, because unlike `Vehicle` there is no phone-only/server-owned split to
keep apart. Room v51 -> v52, UNAPPLIED.

## Step 3 done 2026-08-29: `drives` and `drive_reassignments`, together, and it is a SIMPLER cutover than step 2's

Both writes are cut over. `com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive` and
`com.kevin.legion.vehicle.VehicleController.reassignDrive` now call `FleetEngineStore.recordDrive`/
`recordDriveReassignment` instead of `db.driveDao()`/`db.driveReassignmentDao()` directly - the two
callers that create these rows, so there was exactly one seam each to rewire.

### The read-side question, answered explicitly

Neither table needed a repoint, and neither needed a merge the way ticket 28 owes `service_records`.
Checked, not assumed: `Drive.id` and `DriveReassignment.id` have no reader anywhere outside their own
DAO - no alarm request code, no soft foreign key, no Compose recomposition key. `FleetBackend`'s own
class doc already records why: `drives`/`drive_reassignments` are two of the six tables that already
play the dual role `VehicleReplica`/`ServiceHistoryReplica` were built for - the legacy table itself
is what `FleetReconcile`'s batch refill inserts into (insert-if-absent by `syncId`), so an ordinary
read already serves whatever either channel (the new live push, or the older batch reconcile) wrote.
`b17bc88`'s hazard - a wholesale replica refresh reminting a locally-relied-on id - simply does not
apply here, because reads and writes were never split from the same table to begin with.

### Identity, ruled already, not reopened

`Drive.syncId`/`DriveReassignment.syncId` were the identity before this step (ticket 06's own
ruling, because neither is an engine record) - this step only adds the LIVE per-row push
`FleetReconcile`'s batch job never had. `Drive.serverId`/`DriveReassignment.serverId` (new, nullable,
co-located like `ServiceRecord.serverId`) are bookkeeping only, never consulted to decide insert vs.
update: `DriveUpload`/`DriveReassignmentUpload` upsert by the natural key server-side
(`ON CONFLICT (sync_id)`), so a repost is always free by construction. Room v52 -> v53, UNAPPLIED.

### The per-drop check found one thing worth preserving, not removing

Dropping `drives` from `sync/SyncEngine.kt`'s `REGISTRY` cost nothing else - no other code was gated
on its presence. `drive_reassignments` was different: `SyncEngine.syncNow` used to call
`applyReassignments` (the local re-key of `obd_samples` per stored correction rule) gated on
`drive_reassignments` still being iterated as a registry entry. That re-apply is a LOCAL SQLite
operation with no dependency on which channel populated the table - dropping the registry entry
without decoupling the call would have silently stopped reassignment rules from ever reaching
`obd_samples` again, on every device, regardless of Supabase. The call now runs unconditionally, in
the same position in the pass it always ran (immediately before `obd_samples`' own turn). Opposite
shape from ticket 27's finding and from step 2's own SyncEngine finding: this one would have been a
silent REMOVAL of a still-needed local behaviour, not a silent absence already in place.

### Tests

`FleetEngineStoreDrivesCutoverTest` (9 tests), matching `FleetEngineStoreServiceHistoryCutoverTest`'s
shape with one adaptation: neither `Drive` nor `DriveReassignment` has a domain-level edit call (both
are append-only), so `syncDriveToServer`/`syncDriveReassignmentToServer` are `internal` rather than
`private`, letting the test drive a genuine retry of the same local row directly - the only way to
exercise "a re-run does not remint the local id" for a table with no edit path at all.

## Step 4 done 2026-08-29: `code_events` and `code_clear_events`, and `oil_analyses` is scoped out

Two of the diagnostics trio, cut over together (same shape, same producer pattern). `oil_analyses`
is deliberately NOT built - see below, this is the ticket's own "stop at a coherent boundary" clause
exercised for real, not a shortfall.

### Identity, confirmed not assumed

Both tables were already `hasSyncId = true` in `SyncEngine`'s registry and `SupabaseFleetBackend`
already had working `upsertCodeEvent`/`upsertCodeClearEvent` keyed `onConflict = "sync_id"` -
verified by reading both files rather than trusting the brief. Neither table is an `EngineRecord`,
so `syncId` (already unique server-side) is the identity, and the new `serverId` column on each
(Room v53 -> v54, additive, `DEFAULT NULL`) is bookkeeping only, mirroring `Drive.serverId`/
`DriveReassignment.serverId` exactly - never consulted to decide insert vs. update, since a repost
is always free by construction (`ON CONFLICT (sync_id)` server-side).

### The read-side question, answered explicitly

Neither table needed a repoint. Checked: `CodeEvent.id`/`CodeClearEvent.id` have no reader anywhere
outside their own DAO - no alarm request code, no soft foreign key, no Compose recomposition key
(`CarToolbelt`, `FleetDigestBuilder`, `CarAspectSummaries`, `FleetScreen`, `MonthlyRecapController`,
`DailyDriveLogController` all read by `vehicleId`/time range, never by `id`). Same shape as
`drives`/`drive_reassignments` at step 3, for the identical reason: neither table has an
engine-record counterpart, so there was never a replica to compose reads from in the first place.

### The producers, and the one genuinely dormant table

`code_events` has exactly one live writer:
`com.kevin.legion.service.AriaForegroundService.recordCodeEvent`, called from the DTC-watch loop
whenever a new code trips. `code_clear_events` has exactly one:
`com.kevin.legion.vehicle.DtcClearController.recordOutcome`, called for the three clear outcomes
that actually send Mode 04. Both now go through `FleetEngineStore.recordCodeEvent`/
`recordCodeClearEvent` instead of calling their DAOs directly - one seam each, matching every prior
step's shape.

**`oil_analyses` has no live write entry point anywhere in the app, and this is a real finding, not
an assumption.** Grepped every call site of `OilAnalysisDao.insert`: the only one is
`FleetReconcile`'s own batch download/reconcile path (inserting server rows this device has not
seen yet), not a user-facing create. `ui/fleet/OilAnalysisDrilldown.kt`'s two `OilAnalysis(...)`
constructions are Compose `@Preview` fixtures, confirmed by reading the file - they never run
outside Android Studio's preview renderer. The DAO's own comment already called this table
"Dormant" for its delete path; the same is true of its insert path. There is no caller to rewire and
no live producer to give a push function to, so building one would be schema and code for a write
path that does not exist. `oil_analyses` therefore keeps its Drive-JSON channel in `SyncEngine`'s
`REGISTRY` unchanged - a table whose writes never moved keeps the only cross-device path it has ever
had. A future ticket that gives it a real producer (a voice tool or a UI save action) should retire
this registry entry in the same change, matching this step's own pattern exactly.

### The per-drop check

Dropping `code_events`/`code_clear_events` from `SyncEngine.kt`'s `REGISTRY` costs nothing else -
grepped the whole `sync/` package and found no other code gated on either table's registry
membership (unlike `drive_reassignments`' `applyReassignments` call at step 3). `oil_analyses` was
left in the registry, so there is nothing to drop-check for it this time.

### Tests

`FleetEngineStoreDiagnosticsCutoverTest` (11 tests), matching `FleetEngineStoreDrivesCutoverTest`'s
shape exactly: neither `CodeEvent` nor `CodeClearEvent` has a domain-level edit call, so
`syncCodeEventToServer`/`syncCodeClearEventToServer` are `internal` for the same reason
`syncDriveToServer`/`syncDriveReassignmentToServer` are, letting the test drive a genuine retry of
the same local row directly.

### Verification

1. `./gradlew compileDebugKotlin -Pnokey` - green.
2. `./gradlew testDebugUnitTest -Pnokey` - green, 2,756 tests (JUnit XML totals), up from a
   baseline of 2,745 - the 11 new tests, nothing else moved.
3. `app/schemas/` - `54.json` is new; `1.json` through `53.json` are byte-unchanged (`git diff
   --stat` on `53.json` is empty). `CarDatabase.SCHEMA_VERSION` bumped 53 -> 54 alongside
   `@Database(version = 54)`.
4. `python tools/docs_check.py` - clean ("no drift").
5. Migration written: `MIGRATION_53_54` (`code_events.serverId`, `code_clear_events.serverId`,
   both `TEXT DEFAULT NULL`, additive). **UNAPPLIED** - not run against a real device.
6. **Unproven on device.** Nothing in this step has been exercised against a live Supabase project
   or a physical phone - the whole thing is Robolectric plus a fake `FleetBackend`, same posture as
   every prior step in this ticket.
