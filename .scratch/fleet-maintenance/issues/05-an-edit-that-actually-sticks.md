# An interval edit that actually sticks

Type: grilling
Status: open

## Question

Kevin asked the assistant to change the oil interval from 3,000 to 7,500. It said it had. It had
not.

**It could not have.** There are three write sites that touch `MaintenanceItem.intervalMiles` and
only one of them is live:

| Path | file:line | Reachable? |
|---|---|---|
| `applyServiceIntervals` → `insertAll` | `VehicleController.kt:674` | Yes, but the DAO is `@Insert(IGNORE)` (`MaintenanceItemDao.kt:22-23`) - **it cannot overwrite an existing row** |
| `refreshServiceIntervals` | `VehicleController.kt:713-736` | **No. Zero callers in `app/src/main`. Dead code.** |
| `AdvisorProposalExecutor.setMaintenanceItem` | `AdvisorProposalExecutor.kt:201-224` | Yes - voice only, only via `accept_proposal` on a stored FLEET advisor proposal |

`MaintenanceItemDao` has **no update query and no delete query**. Nothing under `ui/` calls it for
anything but `getForVehicle`.

**This is the map's core defect class**: a write that reported success and did nothing. CLAUDE.md §4
rule 6 names the same sin for reads - silently dropping a row you did not recognise. This ticket
owns making it structurally impossible on the write side.

## What has to be decided

1. **The DAO surface.** What does `MaintenanceItemDao` need - `update`, `delete`, an upsert that
   genuinely upserts? Note that `upsert`/`upsertStamped` already exist (`:13-18`) and re-stamp
   `updatedAt`; the gap is that nothing calls them from an edit path and there is no delete at all.
2. **Who owns an interval once Kevin edits it.** After a hand edit, can the LLM seed ever overwrite
   it? `insertAll(IGNORE)` accidentally gives the right answer today (it can't), but by accident,
   and `refreshServiceIntervals` - if anyone ever wires it up - would blow straight through.
   **Decide the rule and make the code enforce it**, rather than relying on which DAO annotation
   happened to be chosen. This interlocks with ticket 06's provenance flag.
3. **What `accept_proposal` becomes.** Today the advisor is the *sole* live author of intervals.
   Once Kevin can edit directly, is the advisor still allowed to write, or does it become a
   proposer whose suggestion Kevin accepts on a screen? `AdvisorBriefs.kt:61` allowlists
   `set_maintenance_item`; that allowlist is the lever.
4. **Delete `refreshServiceIntervals`, or wire it up?** Dead code that looks like a working feature
   is exactly what made the assistant's claim plausible. Either it gets a caller or it goes.
5. **What a voice edit does now.** There is no `set_maintenance_interval` Live tool. Should there
   be, or does an interval edit become screen-only? Kevin's actual attempt was by voice, which
   argues for the tool - but a tool that writes must be one that cannot silently no-op.
6. **The no-op guard, stated as a rule.** What does the codebase do so that "reported success,
   wrote nothing" cannot recur? Candidates: every write path returns the affected row count and a
   zero is an error, not a shrug; the assistant reads the value back before confirming; a write to a
   non-existent `(vehicleId, serviceName)` pair is a hard failure rather than an insert. **Pick
   one and make it the rule for every write on this map**, not just this one.

## Watch for

`MaintenanceItem`'s primary key is composite - `(vehicleId, serviceName)`. **Renaming a service item
is therefore a delete plus an insert, not an update**, and it will silently orphan the anchors if
done carelessly. Ticket 07 owns the delete semantics; this ticket must not design a rename that
assumes a surrogate key exists.

## Verification

On the device, with Kevin's real data: change the oil interval to 7,500, force-stop the app, reopen
it, and read the value off the screen. Then pull the DB and confirm the row. **Two independent
confirmations, because the failure being fixed is precisely one of a confirmation that lied.**
