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
