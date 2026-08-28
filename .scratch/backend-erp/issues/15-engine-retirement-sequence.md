---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# Engine retirement is a REWIRE of the unconfigured path, not a deletion

**Scoped 2026-08-27 before starting. Ruling 7 says the generic engine retires and Room mirrors the
per-aspect tables. The word "retire" makes it sound like phase 6 deletion work. It is not.**

## What actually blocks deletion

Every dual path built in phase 4 branches the same way. `PlaceController.all` is the clearest:

```
configured   -> placeDao().getAll()      // the legacy `places` table, serving as replica
unconfigured -> the ENGINE
```

**The unconfigured path IS the engine.** And unconfigured is not a corner case - it is clone-and-run,
a hard requirement from the 2026-07-30 pivot: a stranger clones, sideloads, signs in, and it works,
with no Supabase project of their own. Delete the engine today and that install has no local store
at all.

So retirement is: **repoint every unconfigured read AND write from the engine to the legacy typed
table, per aspect, then delete.** That is ruling 7's real content ("Room mirrors the per-aspect
tables"), and ticket 05 already said the hard part out loud: *reconcile-and-repoint per aspect,
never blind-switch*, because the legacy tables have been writer-less since the 2026-08-24 cutover
and are stale by however long.

## Measured

- `engine/` is 31 files, 5,599 production lines (the 9,518 figure in ticket 05 counted differently -
  read the code, not the older number).
- **34 non-engine files** consume `engineRecordDao()` / `RecordStore` / `PayloadCodec`.
- Five reconciles, `IngestPipeline`, `CalendarImportController` and the whole `data/local` engine
  entity set are among them.

## Per-aspect difficulty, which is NOT uniform

**Places - easiest, and the right first slice.** The legacy `places` table already exists, already
serves the configured read, and is already written on ACK. Repointing the unconfigured path at the
same table makes one store serve both. 3 records.

**Pantry - similar.** `pantry_receipts`/`pantry_line_items` already serve the configured path.

**Ledger - blocked on its own ticket.** `IngestPipeline.commit` is the biggest single consumer, and
ticket 03 says the engine retirement lands WITH the `commit_statement` RPC move, not before or
after. That pairing is the whole reason the commit is expensive to move: `RecordStore`'s per-row
fan-out is what makes it 200+ statements, and retiring the engine is what removes it.

**Notes+Dates - hardest, and the legacy table is a trap.** `list_items` has had no production writer
since cutover 1 and is badly stale. The live truth is in the engine (56 Items) and, when configured,
`events_replica`. Repointing the unconfigured path at `list_items` without a reconcile would serve
months-old todos. Worse, `ListItem.id` is an `AlarmManager` request code and a soft foreign key from
three tables - the exact hazard `b17bc88` and the 51-false-missed incident were both about.

**Fleet - already answered, differently.** Ticket 14 ruled fleet a projection: reads stay
legacy-primary and were never engine-primary for the local path in the same way. Fleet's engine use
is `FleetEngineStore` and the reconcile, and it should be checked against that ruling rather than
assumed to follow the others.

**CHECKED 2026-08-27, step 3 (ticket 16).** Vehicle needed no repoint - it was already
unconditionally legacy-primary, both reads and writes, and now has a regression test pinning that.
`ServiceHistory`/`MaintenanceSchedule` are a SEPARATE problem ticket 14 never ruled on: they are
engine-only unconditionally (no configured/unconfigured split to repoint at all), by cutover 4's own
design, and repointing them onto a legacy table is a real schema decision, not a copier. See
ticket 16. Step 6 ("delete `engine/`") cannot happen until that ticket is ruled and built.

## The sequence, recommended

1. **Places** - one table serves both paths. Smallest, and it establishes the shape.
2. **Pantry** - same shape, slightly more surface.
3. **Fleet** - reconcile against ticket 14's projection ruling rather than copying steps 1-2.
4. **Notes+Dates** - needs a real engine-to-`list_items` reconcile first, with id preservation, and
   it should not be attempted until the id question is settled the way `b17bc88` settled the
   replica's.
5. **Ledger** - last, WITH the `commit_statement` RPC move, per ticket 03.
6. **Only then** delete `engine/`, its entities, its migrations' tables, and the 6,367 test lines.

**Nothing is deleted until every aspect above is repointed and soaked.** Every rollback in this map
depends on the code deleted at the end still existing during the middle - ruling 8 removed the
offline queue, so there is no buffer to hide a bad cutover behind.

## The open question this raises, and it is Kevin's

Phase 4 built the configured path to read REPLICAS. Retirement points the unconfigured path at
LEGACY tables. For places and pantry those are the same table, so one store serves both and the
result is simple.

**For Notes+Dates they are two different tables** - `events_replica` and `list_items` - and keeping
both would mean the same aspect stored two ways depending on a setting, which is the shape that
produced the 51 false "missed". Options: point the unconfigured path at `events_replica` too (one
table, but it is named and shaped as a replica of something), reconcile and use `list_items` for
both (the replica becomes redundant), or accept two stores with a very sharp boundary.

Recommend deciding that BEFORE step 4, not during it.

## RULED 2026-08-27: notes gets ONE local table, and it is the events shape, not `list_items`

Delegated to me ("go everything as per your recommendations"); open to reversal.

The question this ticket flagged: places and pantry had replica and legacy as the SAME table, so one
store serves both paths. Notes has two - `events_replica` and `list_items` - and keeping both would
mean one aspect stored two ways depending on a setting. That is the shape that produced the 51 false
"missed".

**The unconfigured path repoints onto the EVENTS table, not `list_items`.** Three reasons:

1. **`list_items` is the pre-merge shape.** Ruling 4 merged todos into events; the per-aspect table
   for this aspect IS events. Ruling 7 says Room mirrors the per-aspect tables, and mirroring
   `public.events` is exactly what the events table already does.
2. **`list_items` has had no live writer since cutover 1** and is badly stale. Repointing onto it
   would need a full engine-to-`list_items` reconcile first, to arrive at a shape that is wrong
   anyway.
3. **It already carries `kind`**, so the reminder/appointment discriminator that fixed the 51-missed
   incident keeps working on both paths rather than existing on only one.

**The table is renamed, because the name would otherwise be a lie.** A table called
`events_replica` that is ALSO the primary store on an unconfigured install is misdescribed, and this
codebase has been bitten twice by a name or comment that promised something the code did not do
(`EventReplicaDao.upsert`'s defeated guarantee, `GeneratedFormScreen`'s "PHOTO ON FILE"). It becomes
the local `events` table, serving both paths. `list_items` becomes dead and is deleted in phase 6
with everything else.

**The id contract is the whole risk and it is preserved by construction.** `ListItem.id` is an
`AlarmManager` `PendingIntent` request code and a soft foreign key from `list_item_skips`,
`workout_set_logs` and `muted_reminders`. Today the unconfigured path hands out `records.id` and the
configured path hands out the events-table id, which `b17bc88` already made EQUAL to `records.id` by
deriving it. So the copier must seat each engine record at its own `records.id` in the events table -
then both paths hand out the same id they always did, and no alarm, skip, mute or workout log
detaches.

**That is the assertion the step lives or dies on**, and it must be tested directly rather than
inferred from a passing suite.
