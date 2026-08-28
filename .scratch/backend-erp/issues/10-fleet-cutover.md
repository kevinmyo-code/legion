---
type: build
status: built
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

## RULED 2026-08-28: recaps STAY ON THE PHONE. No view, no RPC.

The last modelling call this ticket deliberately left open. Made on Kevin's standing delegation
("complete all tickets with default recs"), and open to reversal - the reasoning is written out so
a reversal has something to argue with.

`vehicle/MonthlyRecapController.kt` and `vehicle/MpgTrust.kt` remain the only implementation of
recap arithmetic. Nothing is transcribed into SQL.

**Why, in the order the reasons actually carry weight:**

1. **A second implementation is the expensive half, and the corpus is the real bill.** Ticket 03
   ruling 2 does not merely prefer a shared corpus for arithmetic that exists twice - it made one
   the deliverable, and the gate corpus is the reason the two gate implementations are *proven* to
   agree rather than merely both existing. Moving recaps server-side buys a view and owes a corpus.
   The ticket already says transcribing these two files unchecked is exactly the hazard ruling 2
   exists to stop.
2. **Ruling 06 already declined to store recaps**, precisely because they are recomputed from
   `drives`. A view or RPC would be a second *derivation* of data the phone derives correctly today,
   which is the same duplication in a different costume.
3. **The consumer does not exist.** The laptop surface is coming, not here. Server-side recap
   arithmetic built now has one hypothetical caller and no way to be wrong loudly.
4. **Deferring costs nothing that deciding now saves.** `drives` and `drive_reassignments` are both
   on the server already, so the INPUTS are durable and queryable whatever happens next. Whenever a
   laptop surface genuinely needs recaps, every fact it needs is sitting there and the decision can
   be made against a real requirement instead of an imagined one.

**The binding condition if this is ever reversed:** recaps move server-side only WITH a shared test
corpus both implementations read, exactly as ticket 03 ruling 2 required for the gate. Not "and
then a corpus later" - the corpus is what makes the move safe, so it lands in the same commit.

**Consequence for `MpgTrust` specifically:** its trust bands are a judgement about data quality, not
an anchor, so CLAUDE.md section 4 rule 5 applies to anything it renders and it stays labelled an
estimate wherever it appears. That is unchanged by this ruling and is stated so a future server-side
port does not quietly drop the label at the boundary.

## RESOLVED 2026-08-28. The last build item landed, and the "done means" bar was amended, not met.

**`car_tasks` folds into `public.events` at a THIRD kind, `car_task`.** Ruling 06 said car tasks get
no table of their own; ticket 14 said fleet is a one-way projection. Both hold: `FleetReconcile`
gained a car-task wave that uploads through `EventsBackend`, the phone keeps `car_tasks` as its
local store, and nothing pulls a server car task back down.

**Why a third kind rather than reusing `reminder`**, which looked free and is not: `EventsReconcile`
wipes every local `kind = reminder` row and refills it from the server, so a car task stored as a
reminder would land in the phone's Notes store on the next reconcile and `AlarmScheduler`'s sweep
would treat it as a reminder it owns. That is the 2026-08-26 51-false-missed incident replayed one
column over. A distinct kind removes it by construction rather than by a filter someone has to
remember.

**Three latent bugs in `EventsReconcile` were closed on the way**, all of which a third kind would
have triggered on the very next run:
- the refill's `partition { kind == APPOINTMENT }` bucketed EVERY other kind as a reminder. Replaced
  with explicit per-kind filters, so an unknown future kind matches neither bucket instead of
  defaulting into the dangerous one.
- the origin-guid retraction pass would have soft-deleted every car task this phone ever uploaded,
  because a fleet `CarTask.syncId` is never in a Notes+Dates `engineGuids` set.
- the `onlyOnServer` diff would have reported every car task as drift, forever.

`vehicle_id` is left NULL on every uploaded row and that is deliberate: `CarTask` is **global, never
keyed to a vehicle**, by its own entity doc. The column stays for the day that changes. `category`,
`done` and `doneAt` go to `structured_meta` rather than into invented events columns.

**Migration `20260828000100_events_kind_car_task.sql` is UNAPPLIED and owed by Kevin.** It finds the
old constraint by the column it covers (`conkey = {kind}`) rather than by the name Postgres probably
gave it - a `drop constraint if exists <guessed name>` that misses would no-op silently, add a
second constraint, and leave every `car_task` insert rejected at runtime while the editor reported
success. That is lesson L37's shape and it is cheap to rule out here.

### The bar this closes on

The "done means" written at the top of this ticket - phone renders from the replica, every fleet
table out of the `SyncEngine` registry - **was amended by ticket 14 and is not the bar any more.**
Fleet is a projection: reads stay legacy-primary and the registry entries STAY, because Drive is
still how fleet syncs between two phones and Postgres is write-only from the phone.

What is actually done: all ten fleet tables have an upload wave, the schema is applied and verified
on the live project, the reconcile is reachable from the migration screen, and recaps are ruled to
stay on the phone.

**Owed on hardware, and none of it is code:** `runFleet` has never been tapped, so the projection is
unproven end to end; the new migration is unapplied; and the diff has never been read against real
data. Suite: 2,842 tests, 0 failures.

## RUN ON THE A25, 2026-08-28. The projection works, and it surfaced two defects.

First real end-to-end run against `HomeERPBackend`, after the `year 0` skip fix. Server counts
queried from the catalog and cross-checked against what the screen said, table by table.

| table | on device | uploaded | on server | agrees |
|---|---|---|---|---|
| vehicles | 5 (3 exportable) | 3 | 3 | yes |
| service_history | 5 | 4 | 4 | yes |
| drives | 17 | 17 | 17 | yes |
| code_events | 59 | 59 | 59 | yes |
| code_clear_events | 2 | 2 | 2 | yes |
| vehicle_specs | 4 | 3 | 3 | yes |
| build_entries | 1 | 1 | 1 | yes |
| drive_reassignments | 1 | 0 | 0 | **wording defect, below** |
| events (car_task) | 14 | 13 | 13 | **defect, below** |

**The skip and its cascade behaved exactly as designed.** Both year-0 placeholders were named with
their guids and the reason. Three child rows - a service record, a vehicle spec and the drive
reassignment - were held back with "vehicle not yet migrated" rather than uploaded against a guessed
parent, and the screen said so in words.

### Defect 1: a guid that already exists under ANOTHER kind silently strands a row, forever

One car task uploaded as 13 of 14 and the 14th was reported "only on this device". It has no vehicle
dependency, so the skip cascade is not the cause.

**The row IS on the server** - `origin_guid = e7546107-...`, title "fix fuel pump relay fault and
transmission electrical issue for cher...", **`kind = 'reminder'`**. It had already been uploaded by
the Notes path under the same guid.

`FleetBackend.uploadMigratedEvent`'s existence guard is **table-wide on `origin_guid`**, so it found
the row and correctly declined to insert a duplicate. But the car-task wave's diff reads
`fetchActive().filter { kind == CAR_TASK }`, which is **kind-scoped**. A guid that exists under a
different kind therefore satisfies the guard and fails the diff at the same time: the row can never
upload and never stops being reported as drift. `isClean` can never go true.

**And the row is now a `reminder` on the server holding a car task's text**, which is the exact
hazard `20260828000100`'s third kind was introduced to prevent, arriving by a route that migration
did not consider. Needs a ruling: does the guard become kind-scoped (allowing the same guid under two
kinds), does the wave re-kind an existing row it owns, or is a cross-kind guid collision itself the
thing to forbid? Not decided here.

### Defect 2: "already all on the server" was said about a table with nothing on it

The screen reported `Drive reassignments: 1 on this device, already all on the server. NOT clean.`
The server has **zero** drive_reassignments. The single row was held back because its vehicle was
skipped - correctly, and it is named in the held-back list two lines further down.

So the phrase is generated from "0 uploaded this run" and asserts a fact it did not check. CLAUDE.md
section 7's outcome-verb rule is about speech, but the principle is the same one: a rendered line
must not assert a state nobody verified. It should say nothing was uploaded and why, not that the
server already has it.

Both defects are wording-and-diff level. **No data was written wrongly and nothing on the device
changed.**

## RULED 2026-08-28 (Kevin): "kind-scope the guard and fix the wording."

Defect 1 is fixed by widening rather than by forbidding: the existence guard, the unique index it
depends on, and the fake test double all move from keying on `origin_guid` alone to keying on
`(origin_guid, kind)` - `SupabaseEventsBackend.uploadMigratedEvent`, `EventsBackend.uploadMigratedEvent`'s
doc, `supabase/migrations/20260828000200_events_origin_guid_per_kind.sql` (**UNAPPLIED**, same
posture as `20260828000100`), and `FleetReconcileTest.FakeEventsBackend`. Defect 2's renderer fix is
tracked in the same commit; see `ui/settings/BackendMigrationResolver.kt`.

### NEW OPEN QUESTION, not resolved here: is the guid collision systemic across all 14 car tasks?

Traced, not guessed. **`CarTask.syncId` for every row that predates `MIGRATION_9_10` (2026-08-11
era, `.scratch/notes-lists-calendar/`) is the SAME string as a Notes `ListItem.syncId`, by
construction**: that migration's `car_tasks -> list_items` copy (`Migrations.kt`, the "Car" list
insert) selects `syncId` verbatim from `car_tasks` into the new `list_items` row - "every column
that exists on both sides copied verbatim" is the migration's own comment. `EngineDataMigrationWave1`
(`copyNotesIfNeeded`) then makes that same value the engine record's `guid` directly - "wave 1's own
copy reused `syncId` as `guid`", its own doc comment - and `EventsReconcile` uploads that engine
record to `public.events` as `kind = reminder` using the guid as `origin_guid`.

**`car_tasks` has been a dead table since that fold - "traced (grep, confirmed by reading
ReminderController.kt and TelemetryRecorder.kt) - nothing in live code writes a new row into
either one anymore"** (`docs/architecture/wave1-carve-2026-08-23.md`). No production code path
constructs a `CarTask` at all today (grepped: the only non-`data/local` references are
`FleetReconcile`'s read, `TelemetryRecorder`'s tombstone-purge sweep, and this ticket's own test
helper). That means **every `CarTask` row that still exists on the device necessarily predates the
fold** - there has been no way to create a new one since - and therefore necessarily has a sibling
`ListItem`/engine-record/`reminder` row sharing its `syncId`/guid.

**Reasoned, not confirmed against the actual device data: all 14 car tasks are at risk of the same
duplication, not just the one observed.** The one difference that could narrow this - a car task
created, then later hard-deleted from `list_items` (its Notes sibling gone) while the `car_tasks`
row survives - is possible in principle (`list_items` has no FK back to `car_tasks` preventing
independent deletion) but not something this pass can rule in or out without querying the live
`list_items`/`events` tables on the actual phone. Confirming the true count needs either an
on-device query (14 `car_tasks.syncId` values against `public.events.origin_guid`) or a second real
`runFleet` pass read against the server the way the first one was.

**Not a ruling, not a cleanup instruction** - per Kevin's own scope for this pass, no existing row
is touched. Left here as the next open item: whether the fold now needs to also re-kind or dedupe
the pre-existing `reminder` siblings, or whether "the same task text exists under two kinds
server-side" is an accepted, permanent consequence of `car_tasks` never having been a single source
of truth in the first place.
