---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# A vehicle row is co-owned, and every prior cutover assumed rows are not

**Found 2026-08-27 attempting the fleet dual path. It is not a gap in the work so far - it is a
shape the earlier aspects never had, and the pattern being copied cannot express it.**

## What stopped

Places, pantry and notes all cut over the same way: the server owns the whole row, so a configured
read serves the replica and a configured write goes to the server and lands in the replica on ACK.
Fleet cannot do that, for two independent reasons.

**1. There is no live write primitive, and that was deliberate.** `FleetBackend` has only
`uploadMigratedVehicle`/`uploadMigratedServiceHistory` - insert-only, guarded by an `origin_guid`
existence check, built for the one-time migration replay. `FleetBackend.kt:44` says so outright, and
wave 1's own commit message called the absence a deliberate scope decision rather than an oversight.

There is also no natural key to upsert against. `places.label` is `UNIQUE`, which is exactly what
makes `PlacesBackend.upsert` a single find-or-create round trip. `vehicles` has no such column:
`20260826000100_origin_guid.sql` explicitly rejected a unique `vehicles.name` as a real product
decision that could not be validated from a machine with no project access. The only unique column
is `origin_guid`, and that same migration's header calls it **"migration PROVENANCE, not identity"**.
Repurposing it as the live-write key is a decision, not an implementation detail.

**2. And this is the deeper one: a `Vehicle` row is CO-OWNED.**

`VehicleReplica` carries what the server owns - name, make, model, year, trim, engine, confirmed,
odometer baseline. The legacy `Vehicle` also carries columns the server deliberately does NOT have,
because ticket 01 ruling 10 kept them phone-only: `personaPrompt`, `voiceName`, `personaTraits`,
`archived`, `onboarded`, `lastOdometerPromptAt`, `tripMilesSinceBaseline`.

**Those are not vestigial. Measured by grep across `ui/` and `vehicle/`:** `archived` is read 15
times, `tripMilesSinceBaseline` 5, `onboarded` 3. A configured read served from the replica drops
them, and the roster UI breaks outright.

That is a sharper failure than the notes cutover's. There the read returned rows it should not have
and a sweep acted on them. Here the read would return rows MISSING COLUMNS the caller depends on -
not a false positive, a structural hole.

## Why no amount of care in the dual path fixes it

Every prior aspect's row had ONE owner. A vehicle has two: the household's Supabase project owns its
identity and specs, and this specific phone owns its persona, its archive state and its telemetry
accumulators. No single-table read can serve both, and the replica was never built to.

## The options, none chosen - this is Kevin's call

1. **A local sidecar for the phone-owned columns**, joined on read: the replica supplies the server's
   fields, a small local table supplies persona/archive/telemetry, and the read composes them. Keeps
   ruling 10 intact (phone-only data stays phone-only) at the cost of a join and a new table.
2. **Widen the server schema to carry them.** Simplest to read, and it contradicts ruling 10 - which
   kept OBD live state, persona and widget layouts phone-only on purpose. Reopening that is a real
   decision, not a shortcut.
3. **Fleet reads stay legacy-primary permanently**, and the replica is only ever a diff and audit
   surface - fleet syncs to Postgres for the laptop surface and for durability, but the phone keeps
   reading its own tables. Cheapest, and it means fleet never really "cuts over" in the sense the
   other four aspects did.

**The live-write identity key is a second, smaller decision** riding on the same ticket: reuse
`origin_guid` despite its own migration comment disclaiming that use, or add a purpose-built key.

## What is safe to build before the ruling

Nothing that changes a fleet READ. The upload path (waves 1-4) is complete and inert, and can be
made runnable from the migration screen so the data lands server-side and can be diffed - that is
useful, reversible, and touches no read. Everything past that waits.

## Sequencing consequence

`.scratch/backend-erp/issues/10-fleet-cutover.md`'s "done means" bar - writes land server-side, the
phone renders from the replica, every fleet table out of the `SyncEngine` registry - **cannot be met
until this is ruled.** And the registry drops in particular must not happen: ruling 05 ties each drop
to the commit its writes move, and fleet writes have not moved. Dropping now would leave those
tables with no sync channel at all, silently.

## RULED 2026-08-27: option 3. Fleet is a PROJECTION, not a cutover.

Kevin delegated this ("go end to end with recommendations. i trust u and its low stakes data"), so
this is my call made on his authority and it is open to reversal. The reasoning is written out so a
reversal has something to argue with.

**Fleet reads stay legacy-primary. The Postgres tables are a one-way projection for the laptop
surface and for durability, not a sync channel.**

Why not option 2 (widen the server schema): ruling 10 kept persona, OBD live state and telemetry
phone-only for stated reasons. Reopening that to solve a read-path inconvenience trades a
deliberate boundary for convenience.

Why not option 1 (local sidecar joined on read): it adds a table and a join to buy the phone
something it already has. The phone is where fleet data originates - the dongle, the drives, the
odometer. Reading it back from a replica of itself is motion without benefit.

**What the projection actually buys, stated plainly so it is not mistaken for a full cutover:** the
laptop surface can READ fleet data, and the data is durable in the household's own Postgres. What it
does NOT buy: a fleet edit made on the laptop reaching the phone. If that is ever wanted, this
ruling is the thing to reverse, and option 1 is the way to reverse it.

**Consequence for ruling 05, and it is a real amendment.** Phase 4's "done means" bar - writes land
server-side, the phone renders from the replica, every fleet table out of the `SyncEngine` registry -
does not apply to fleet. **Fleet KEEPS its SyncEngine registry entries**, because Drive remains the
channel that syncs fleet between two phones. That is not the two-channels-fighting case ruling 05
forbids: Postgres is write-only from the phone here, an export rather than a second source of truth,
so there is nothing for the two to disagree about. If fleet ever becomes bidirectional, the registry
drops come back onto the table along with option 1.

**The live-write identity question dissolves with this ruling.** A projection needs no live
upsert-by-natural-key: the reconcile's `origin_guid`-keyed insert-if-absent is exactly the right
primitive for a one-way export, and `origin_guid` keeps the meaning its own migration gave it.

**What to build:** make `FleetReconcile` reachable (a `runFleet` action on the migration screen,
beside places/pantry/events), so the projection can actually be run and diffed. Nothing touches a
fleet read. `VehicleReplica`/`ServiceHistoryReplica` stay as the diff-and-audit surface they already
are, which is what wave 2 built them to be.

## RESOLVED 2026-08-28. The ruling is built and reachable.

`BackendMigrationScreen.runFleet` exists and calls `FleetReconcile.run` with
`SupabaseFleetBackend`, beside places/pantry/events, with its own doc comment stating in words
what the projection is and is not ("it touches no fleet read: the phone keeps reading its own
tables, and Drive keeps syncing fleet between the two phones exactly as it does today").
`BackendMigrationResolver.renderFleetReport` words the result. That was the whole of "what to
build" in the ruling above.

**Nothing else in this ticket owes code.** The two rejected options (server-schema widening, local
sidecar) are recorded above with their reasoning so a reversal has something to argue with; the
live-write identity question dissolved with the ruling, as stated.

**What is deliberately NOT true, and must not be read into this closure:** fleet did not "cut
over". Fleet reads are legacy-primary, fleet keeps its `SyncEngine` registry entries, and a fleet
edit made on the laptop surface does not reach the phone. Ticket 10's "done means" bar is amended
accordingly and closes on the projection bar, not the cutover bar.

**Owed on the phone, not by this ticket:** `runFleet` has never been tapped on the A25. It is a
one-way export against the household's own project; the projection is unproven until it runs.

## REVERSED 2026-08-28 (Kevin). Fleet becomes a real cutover.

*"we need to build fleet. obd, maintenance etc. all gotta go to supabase."*

**The projection ruling is withdrawn.** Fleet stops being a one-way export and becomes a cutover
like every other aspect: writes land server-side, and the PC surface can read fleet data as a first
class citizen rather than a stale snapshot of the last time somebody tapped a button.

### Why the original ruling was right then and wrong now

It rested on one sentence: *"the phone is where fleet data originates - the dongle, the drives, the
odometer. Reading it back from a replica of itself is motion without benefit."* That was true when
the phone was the only surface. **ADR 0040 made the PC the general client**, and a projection that
nobody can write to means fleet is the one aspect the web app can only look at. `runFleet` being
manual makes it worse: the laptop's view is as stale as the last tap.

### The blocker this ticket was originally about has not gone away

A `Vehicle` row is **co-owned**, and that is still true. `VehicleReplica` carries what the server
owns; the legacy `Vehicle` also carries `personaPrompt`, `voiceName`, `personaTraits`, `archived`,
`onboarded`, `lastOdometerPromptAt`, `tripMilesSinceBaseline` - phone-only by ticket 01 ruling 10,
and measured as live: `archived` read 15 times, `tripMilesSinceBaseline` 5, `onboarded` 3.

**Option 1 is now the path** - the one this ticket described and rejected: a local sidecar table for
the phone-owned columns, composed on read. The rejection reasoning ("it adds a table and a join to
buy the phone something it already has") only held while the phone was the sole surface. It is now
the cost of letting a second surface write.

**Also still true:** there is no live write primitive. `FleetBackend` has only the insert-only
`uploadMigrated*` methods built for replay, and `vehicles` has no natural key to upsert against -
`20260826000100` explicitly rejected a unique `vehicles.name`, and `origin_guid` is provenance, not
identity. **The cutover needs a real upsert and an identity decision**, which the projection ruling
let it dodge.

### The open question this ruling does not answer

**Do raw `obd_samples` go too?** Ruling 10 kept them phone-only, and the reason was volume, not
principle: 18,694 rows on Kevin's device by 2026-08-16, growing continuously while the engine runs,
and they needed an index before telemetry queries stopped table-scanning.

"OBD gotta go to supabase" plainly covers drives, codes, maintenance and vehicle state. Whether it
covers the raw sample stream is a storage decision on a 500 MB free tier, and it should be answered
deliberately rather than by reading the sentence broadly. **Recommendation: drives, codes,
maintenance and specs go; raw samples stay phone-only until something on the PC actually needs
them.** Nothing is lost by deferring - the samples are already on the phone and can be uploaded
later if a use appears.

### Consequences

- `SyncEngine` registry drops come back onto the table. Ruling 05 ties each drop to the commit its
  writes move; that now applies to fleet after all.
- The two-phones-via-Drive channel for fleet retires with it, the same way it did for every other
  cut-over aspect.
- Ticket 10 closes on the projection bar; **a new build ticket owns the cutover.**
