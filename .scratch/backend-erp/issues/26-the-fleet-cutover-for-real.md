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
