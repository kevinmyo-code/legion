# Hand-added items, and what "delete" means under sync

Type: grilling
Status: open
Blocked by: 05   # resolved 2026-08-15 - UNBLOCKED

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
