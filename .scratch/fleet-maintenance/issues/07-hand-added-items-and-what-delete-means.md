---
map: fleet-maintenance
ticket: 07
title: "Hand-added items, and what \"delete\" means under sync"
type: grilling
status: resolved
status-detail: 2026-08-15
blockers: ["05"]
blocked-by: ["[[05-an-edit-that-actually-sticks]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Hand-added items, and what "delete" means under sync

## Question

Kevin wants to add tire rotations and a transmission fluid flush by hand, and to delete items he
does not care about. Neither is possible today: `MaintenanceItemDao` has **no delete query**, and
nothing in `ui/` writes to the table at all.

Adding is the easy half. **Deleting is the trap.**

## The delete trap

`maintenance_items` is synced to Drive as
`Spec("maintenance_items", listOf("vehicleId","serviceName"), Mode.LWW, naturalPk = true)`
(`sync/SyncEngine.kt:184`).

**Last-write-wins on a natural primary key has no way to represent absence.** Delete a row locally,
sync, and the other device's copy - which still has it - wins on the next merge and resurrects it.
This is the same class as CLAUDE.md §2's open finding that *Drive has no compare-and-swap, so
today's shared-file last-write-wins sync will silently lose rows*, and it is why `Vehicle` chose
`archived` over `DELETE` (`Vehicle.kt:54-62`), with the reasoning written out on the entity.

So a delete affordance cannot be built without ruling on this first.

## What has to be decided

1. **Delete or archive?** `Vehicle` set the precedent: hide, don't destroy, and let the flag ride
   the LWW path on `updatedAt` so the hiding propagates for free. Does `MaintenanceItem` follow it?
   The counter-argument is that a maintenance schedule is small, curated, and Kevin genuinely wants
   rows *gone* rather than filed away - and an archived-item list is one more thing to explain.
2. **If archive: what does an archived item do to history?** `lastDoneMileage` on an archived item
   is real history. Is it still readable? Does un-archiving restore the anchor?
3. **If real delete: what carries the tombstone?** A deleted-ids list in the sync payload, a
   per-row `deletedAt`, or an explicit acceptance that a delete may not propagate across devices.
   The third is defensible for a two-phone solo app, but **only if it is stated rather than
   discovered**.
4. **What fields a hand-added item has.** `serviceName`, `intervalMiles`, `intervalMonths`,
   `lastDoneMileage`, `lastDoneDate`, `neverDone`. Which are required? An item with no interval at
   all renders `"no interval on file"` and draws no meter - is that a legal state to create by
   hand, or should the form insist on at least one interval?
5. **`neverDone` in a form.** It is a genuinely useful three-state distinction that the entity doc
   goes to some length to justify (`MaintenanceItem.kt:24-30`): *never done* is actionable and
   always overdue; *unknown* is neither. **Two booleans' worth of meaning in a UI that will want to
   be one checkbox.** Decide the control, and the words on it.
6. **Duplicate and near-duplicate names.** The PK is `(vehicleId, serviceName)`, so adding
   `"Tire Rotation"` when `"Tire rotation"` exists creates a second row that a case-insensitive
   human reads as the same item. `canonicalizeAndDedupe` (`VehicleController.kt:684-690`) already
   solves this for the seed against a 10-entry keyword table. **Does a hand-added name go through
   the same canonicaliser?** If yes, Kevin cannot name an item something the table does not know.
   If no, ticket 08's name-matching has a much harder job. This is the hinge between the two
   tickets and it must be decided here.
7. **Rename.** Composite PK means renaming an item is delete-plus-insert, and it will orphan the
   anchors unless carried deliberately. Is rename in scope at all, or is delete-and-re-add enough?

## Input

Ticket 02 returns the real 1998 XJ schedule, including items outside the ten canonical keywords -
transfer case fluid, differential fluid, coolant service, PCV. **That list is the test case**: if
the hand-add flow cannot express the factory's own schedule, it is not finished.

## Verification

On the device: add a transmission fluid flush with a real interval, add a tire rotation, delete
something, force-stop, reopen, confirm all three survived. Then pull the DB and confirm the rows.
If sync has ever run, confirm the delete's behaviour across a sync cycle - and if it has not
(`sync/` has never executed, per `memory/MEMORY.md`), **say that the sync half is untested rather
than implying it works.**

---

## Answer (2026-08-15)

### The ticket's premise was wrong, and in a useful direction

This ticket was charted believing a delete could not propagate, and that a tombstone would have to
be invented. **It already exists in this repo, is documented, and works.**

`SyncEngine.kt:46-52`:

> `car_tasks` and `places` carry a `deleted` soft-delete tombstone column (B19): the SELECT *
> snapshot below is deliberately NOT filtered on it, so a tombstoned row ships to Drive and
> propagates through the normal LWW path (a newer `deleted=1` wins like any other edit) instead of
> a hard DELETE being invisible to sync and resurrected on the next pull. Every other reader of
> these two tables (DAOs, controllers, tools, UI) DOES filter `deleted = 0`.

And the crucial line, at `:222-226`: the pattern **works there precisely BECAUSE those tables are
LWW.** `maintenance_items` is `Mode.LWW, naturalPk = true` (`:184`). **It qualifies.** The tables
that cannot use it are the UNION ones (`ledger_transactions`), which is not this case.

So the choice was never "delete vs archive vs give up" - it was "use the mechanism already proven
here, or invent a second one".

### Decisions (Kevin, 2026-08-15)

**1. Delete is a real delete, via the existing tombstone.** A `deleted` column on
`maintenance_items`, the sync snapshot deliberately unfiltered, every other reader filtering
`deleted = 0`. Same GC horizon as `car_tasks`/`places` - reuse `TOMBSTONE_HORIZON_MS`, do not
invent a second constant.

**Service records survive.** Kevin declined cascading the delete into history, and that is right
under §4's posture: a `ServiceRecord` is a fact about work actually done, and deleting a schedule
row does not un-do the work. Ticket 11's history screen shows them regardless of whether the
matching item still exists.

**2. A hand-typed name is stored verbatim, with a near-duplicate warning.**

This is the hinge with ticket 08, and it resolves as **two functions with different jobs**:

- **Storage: verbatim.** What Kevin types is what is stored. The canonicaliser must never rewrite a
  name he deliberately chose - `canonicalizeServiceName("Oil filter change")` returns `"Oil Change"`
  (the `"oil"` keyword matches), which would silently merge a new item into an existing one.
- **Detection: the canonicaliser, as a comparator only.** Before inserting, canonicalise both the
  typed name and every existing item's name and compare. A collision raises *"this looks like Oil
  Change - add anyway?"*, and Kevin decides.

The canonicaliser's real job was always matching, never storage. This states that.

**3. Three-way picker for the anchor: never done / don't know / done at ...**

Maps onto the entity's existing three states, which `MaintenanceItem.kt:24-30` goes to some length
to protect and which nothing has ever set - **`neverDone` is `true` on 0 of 54 rows** (ticket 01).
A control that cannot express it is why.

| Picker | Row state |
|---|---|
| never done on this car | `neverDone = true` - always overdue, actionable |
| don't know | both anchors null - never treated as due |
| done at *mileage* / *date* | anchors set, `neverDone = false` |

### A concrete bug this surfaces, and it is the duplicate engine

`canonicalizeServiceName`'s fallback (`:201`) is
`serviceName.trim().replaceFirstChar { it.titlecase() }` - **it titlecases only the first
character.** So a hand-typed `"transfer case fluid"` stores as `"Transfer case fluid"`, while the
LLM seeds `"Transfer Case Fluid"`. Different string, **different primary key, duplicate row.**

That is precisely the mechanism behind the duplicate concepts ticket 01 counted across Kevin's 54
rows (`Air Filter` / `Air Filter Replacement` / `Engine Air Filter`; `Axle Fluid` / `Axle Lubricant`
/ `Axle Lubricant Service`). Decision 2's comparator must therefore compare
**case-insensitively after canonicalisation**, not on the raw strings, or it will fail to catch
exactly the collisions it exists for.

### A property worth keeping deliberately

A tombstoned row is still a row, and the seed writes through `insertAll` = `@Insert(IGNORE)`. So
**a deleted item cannot be resurrected by re-seeding** - IGNORE skips the existing (tombstoned) row.
That is correct behaviour and it should be preserved on purpose rather than left as a happy accident,
which is the same mistake ticket 05 called out about `IGNORE` protecting edited intervals by luck.

**Consequence for ticket 14**: its populate diff must show a deleted item that appears in the
factory schedule as *"you deleted this - add it back?"*, not silently skip it and not silently
restore it. Noted there.

### Rename is out of scope

Question 7. `MaintenanceItem`'s PK is `(vehicleId, serviceName)`, so a rename is a delete plus an
insert, and with a tombstone it becomes tombstone-plus-insert with the anchors carried by hand.
Kevin has not asked for it, delete-and-re-add expresses it, and building it means a migration path
for `service_records` that reference the old name by string. **Ruled out of this map**, not deferred
vaguely - see the map's Out of scope section.

### Room

`deleted` on `maintenance_items`. Additive, `INTEGER NOT NULL DEFAULT 0`.

**Build it together with ticket 06's `intervalSource` and it is ONE migration, v19 -> v20**, not
two. Both are additive columns on the same table, both are gates on the same downstream tickets,
and CLAUDE.md §5's discipline (verbatim generated SQL, `exportSchema`, committed schema JSON,
migration test, no destructive fallback) is the same work once instead of twice.

DAO changes: `getForVehicle` and `get` filter `deleted = 0`; a new `softDelete(vehicleId,
serviceName, now)` returning its row count (ticket 05's law); the sync snapshot untouched so it
still sees tombstones.

### Verification

Binding on whoever builds this (L11):

1. On the device: add `Transmission Fluid Flush` with a real interval; add `Tire Rotation` and
   confirm the **duplicate warning fires** against the existing seeded row; delete `Cabin Air
   Filter`. Force-stop, reopen, confirm all three stuck. **Pull the database.**
2. Confirm the deleted row is **present with `deleted = 1`**, not absent - if it is gone from the
   table, the tombstone was not written and sync would resurrect it.
3. Confirm a re-seed does **not** resurrect the deleted item.
4. Set an item to "never done" and confirm it reads as overdue; set another to "don't know" and
   confirm it does **not**.
5. **The sync half cannot be verified.** `sync/` has never executed on this device
   (`memory/MEMORY.md`). Say that plainly rather than implying the propagation works - the
   mechanism is borrowed from a proven pattern, but this table's use of it is `reasoned`, not
   `tested`.

### Assumptions ledger

- `traced`: the tombstone pattern's existence, its doc, and the LWW-only constraint at
  `SyncEngine.kt:46-52` and `:222-226`; that `maintenance_items` is `Mode.LWW, naturalPk = true`;
  `canonicalizeServiceName`'s keyword-match-then-titlecase-first-char fallback; that
  `insertAll` is `IGNORE`; `SERVICE_KEYWORDS` has 10 entries against ticket 02's 26 factory names.
- `on-device`: `neverDone` true on 0 of 54 rows; the duplicate-concept rows.
- `reasoned`: that the tombstone will propagate correctly for this table. The pattern is proven for
  `car_tasks`/`places`, and `maintenance_items` meets the stated precondition - but **sync has never
  run at all**, so no tombstone of any kind has ever propagated on this device.
- **Not built.** **Unblocks 08.**
