---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# ServiceHistory/MaintenanceSchedule are not a configured/unconfigured split - they are engine-only, unconditionally, by a different cutover's design

**Found 2026-08-27 doing ticket 15 step 3 (fleet).** Not a gap in this step's work - it is the
"check before you copy" ticket 15 itself asked for, and the answer is that fleet splits into two
genuinely different problems, only one of which this step can close.

## What was checked, and what it found

`FleetEngineStore.kt` is the one door for `vehicles`/`service_records`/`maintenance_items`. Its own
class doc (cutover 4, `docs/architecture/cutover4-2026-08-24.md`) already states the shape, and
tracing every function in the file confirms it holds:

**Vehicle needs no repoint.** `getByMac`/`getAll`/`getAllIncludingArchived` read `db.vehicleDao()`
unconditionally - there never was a configured-vs-unconfigured branch here for fleet to retire, the
way places'/pantry's `unconfigured -> the ENGINE` branch was. Every Vehicle-identity write already
writes the engine record AND the legacy mirror in one transaction, and the mirror is what every
read serves from (ticket 14's ruling, ratified: a `Vehicle` row is co-owned, and the mirror is the
only store that can hold both halves). `FleetEngineStoreTest` now pins this as a live contract - two
new tests wipe the engine `Vehicle` record entirely (trash + hard purge, same two calls
`RecordStore.purgeExpiredTrash` chains) and assert `getByMac`/`getAllIncludingArchived` are
unaffected. Proven load-bearing by mutation: temporarily coupling `getByMac` back to
`engineVehicleId(...)` failed the new assertion immediately, then was reverted.

**Ticket 14's own "what to build" is already shipped.** `runFleet` in
`ui/settings/BackendMigrationScreen.kt:144` calls `FleetReconcile.run`, wired the same way as
places/pantry/events. Nothing outstanding there.

**ServiceHistory and MaintenanceSchedule are a different problem, and it is NOT the one ticket 14
ruled on.** They have no configured/unconfigured branch at all - `serviceRecordsForVehicle`,
`getForVehicle`, `insertObserved`, `upsertNewItem`, etc. call `db.engineRecordDao()`/`RecordStore`
unconditionally, with zero legacy writer, by cutover 4's OWN deliberate design (ticket 29's
unification: `ServiceHistory` carries both `OBSERVED` and `ASSERTED` rows in one record type so
`FleetRecordBridge.projectAnchor` has exactly one place to derive "last done" from, and
`MaintenanceItem.lastDoneMileage`/`lastDoneDate` are reconstructed fresh on every read rather than
stored). The legacy `service_records`/`maintenance_items` tables are read by nothing live -
`grep` shows exactly two remaining readers, both migration/reporting remnants, not live writers:
`EngineDataMigrationWave4` (one-time, reads the pre-cutover data at migration time) and
`MonthlyRecapController.generate` (deliberately, per its OWN comment, left reading the stale legacy
table - "a service logged after this branch lands... will under-count here until MonthlyRecap's own
follow-up wave repoints this read", a named and accepted gap, not a silent one, and out of this
ticket's scope).

## Why this is a genuine fork, not a places/pantry-shaped copier

Places and pantry's unconfigured path pointed at the SAME table the configured path already served
from and already wrote on ACK - the repoint was mechanical. ServiceHistory/MaintenanceSchedule have
no such table waiting: the legacy `ServiceRecord` entity has no `kind` column to hold an `ASSERTED`
row (ticket 29's whole point was collapsing two previously-separate concepts into one table so
`projectAnchor` never has two sources to disagree), and `MaintenanceItem.lastDoneMileage`/
`lastDoneDate` in the legacy shape are stored, mutable columns - exactly the "two independently
writable places for the same fact" shape ticket 29 was built to close. Repointing onto the legacy
tables as they stand today would either drop the `OBSERVED`/`ASSERTED` unification (regressing
ticket 29) or require a real schema addition (a `kind` column, a migration, and a rewrite of
`projectAnchor`'s "most-recently-updated-row-wins-both-axes" derivation against a typed table
instead of a payload blob) - a decision, not an implementation detail, and one no existing ticket
authorizes.

## What this means for ticket 15's sequence

Ticket 15 step 3 (fleet) is DONE for the piece it actually owns: Vehicle needed no repoint, and now
has a regression test pinning that. **It does NOT retire fleet's dependency on `engine/` in general**
- `engine/RecordStore`/`engineRecordDao()`/`PayloadCodec` remain the sole store for
`ServiceHistory`/`MaintenanceSchedule`, and step 6 ("delete `engine/`") cannot happen while that is
true. This ticket exists so that gap is visible before step 6, rather than being discovered the day
someone tries to delete `engine/` and two fleet record types have nowhere left to live.

## The options, none chosen

1. **Widen `ServiceRecord` with a `kind` column** (additive Room migration) and give
   `MaintenanceItem` a derived-not-stored anchor, reproducing `projectAnchor`'s current semantics
   against the legacy tables. Closest to the places/pantry shape, but a real schema change plus a
   rewrite of the derivation logic - not a copier.
2. **Leave ServiceHistory/MaintenanceSchedule on the engine permanently** and narrow ticket 15's
   step 6 to "delete everything the engine used to serve for places/pantry/notes/dates/ledger,
   keep the generic tables alive as fleet's own store for these two record types". Cheapest, and it
   means "delete `engine/`" never fully happens - only shrinks to two record types' worth of
   purpose-built infrastructure.
3. **Give ServiceHistory/MaintenanceSchedule their own small purpose-built tables** (not a rename of
   the generic `records` table, a real `service_history`/`maintenance_schedule` schema shaped for
   what ticket 29 actually needs) - closer to option 1 in effort, cleaner in the end state, more
   migration risk given the derivation logic (`projectAnchor`, `explainedBy`) has to move too.

Recommend deciding this before step 6, not during it - same posture ticket 15 itself asked for on
the Notes+Dates fork.
