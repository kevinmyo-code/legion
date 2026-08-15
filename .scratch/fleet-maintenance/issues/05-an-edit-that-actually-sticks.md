# An interval edit that actually sticks

Type: grilling
Status: resolved (2026-08-15)

## Question

Kevin asked the assistant to change the oil interval from 3,000 to 7,500. It said it had. It had
not.

**It could not have.** There are three write sites that touch `MaintenanceItem.intervalMiles` and
only one of them is live:

| Path | file:line | Reachable? |
|---|---|---|
| `applyServiceIntervals` -> `insertAll` | `VehicleController.kt:674` | Yes, but the DAO is `@Insert(IGNORE)` (`MaintenanceItemDao.kt:22-23`) - **it cannot overwrite an existing row** |
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

   **Precedent already set by ticket 13 (2026-08-15):** `VehicleDao.setOdometerBaseline` returns
   the affected row count and `setOdometer` treats `0` as a failure with words, rather than
   reporting "Got it, 142,500 on the clock" for a reading that went nowhere. Pinned by
   `VehicleControllerIdentityWritesTest`. That is the row-count-checked option, adopted once; this
   ticket decides whether it becomes the rule.

7. **`LiveToolbox` asserts `success = true` above every one of these guards** (surfaced by
   senior-dev review of the ticket 13 fix, 2026-08-15). `LiveToolbox.kt:1378` reads
   `result(success = true, message = VehicleController.setOdometer(...))` - so the JSON envelope
   handed to Gemini says the call succeeded even when the message is now the new "I don't have this
   car on file yet" refusal. **The same pattern repeats across most tool wrappers in that file**
   (`log_service`, `set_reminder`, `tag_place`).

   It is pre-existing and was not introduced by the ticket 13 fix, and in practice `message` is the
   channel the model relays - but it is **the same false-success shape one layer up**, which makes
   it this ticket's business rather than a nit to lose. Decide whether the tool envelope's `success`
   flag has to be derived from the write rather than hardcoded, and note the blast radius: it is a
   `LiveToolbox`-wide contract change, not a one-line fix.

## Watch for

`MaintenanceItem`'s primary key is composite - `(vehicleId, serviceName)`. **Renaming a service item
is therefore a delete plus an insert, not an update**, and it will silently orphan the anchors if
done carelessly. Ticket 07 owns the delete semantics; this ticket must not design a rename that
assumes a surrogate key exists.

## Verification

On the device, with Kevin's real data: change the oil interval to 7,500, force-stop the app, reopen
it, and read the value off the screen. Then pull the DB and confirm the row. **Two independent
confirmations, because the failure being fixed is precisely one of a confirmation that lied.**

---

## Answer (2026-08-15)

### The count

| | |
|---|---|
| `MaintenanceItemDao` write methods | **3** - `upsertStamped` (REPLACE), `upsert`, `insertAll` (IGNORE) |
| `MaintenanceItemDao` update queries | **0** |
| `MaintenanceItemDao` delete queries | **0** |
| `maintenanceItemDao()` call sites | 18, of which **4 write** |
| Hardcoded `success = true` in `LiveToolbox` | **23** (out of 194 `result(` calls; the other 54 are computed) |

The four writers: `AdvisorProposalExecutor:217`, `VehicleController:222` (`logServiceDirect`),
`:265` (`logPastServiceDirect`), `:822` (`applyServiceIntervals`, the seed).

### Decisions (Kevin, 2026-08-15)

**1. Once you have edited an interval, only the factory populate may change it, and only through
its diff.**

Refinement added on resolution, because the literal reading excludes something it should not: the
rule is **no write to a driver-owned interval without an explicit confirmation that names the
change.** Two paths satisfy that and are therefore allowed:

- Ticket 14's populate diff, where the change is listed and can be declined.
- `accept_proposal` (`AdvisorBriefs.kt:61` allowlists `set_maintenance_item`) - the advisor
  **proposes**, and the driver accepting IS the confirmation. The advisor stops being an author and
  becomes a proposer, which is what the ticket asked.

Nothing else may. Specifically **the LLM seed may never overwrite a driver-owned interval**, and
today it only fails to by accident - `insertAll` is `@Insert(IGNORE)`, so the protection is a
side effect of an annotation nobody chose for that reason. **Make it explicit and enforced**, not
inherited from a default.

**This needs ticket 06's provenance flag to know which intervals are the driver's.** Ticket 05
decides the rule; 06 supplies the mechanism. **Resolve 06 before either is built** - see Ordering.

**2. Voice and screen both, and voice reads the value back.**

A read-back cannot be produced if the write did not land. `"Oil change is now every 7,500 miles,
last done at 118,483"` requires re-reading the row after writing it. **This is exactly how Kevin's
original attempt should have failed**, out loud, instead of reporting success and changing nothing.

Needs a `set_maintenance_interval` Live tool (there is none today - the only live interval writer
is `accept_proposal`).

**3. Targeted writes for `maintenance_items` too - close the class rather than the instance.**

`MaintenanceItemDao.upsert` is `@Insert(REPLACE)`, the same whole-row overwrite that destroyed the
vehicle row, and `logServiceDirect` (`:216-222`) already does read-modify-write through it: read
the item, `existing.copy(lastDone...)`, upsert. An interval edit and a service log landing close
together can lose one another.

So, mirroring `VehicleDao`:

| New query | Owns |
|---|---|
| `setIntervals(vehicleId, serviceName, miles, months, now)` | intervals only |
| `setAnchor(vehicleId, serviceName, mileage, date, now)` | `lastDoneMileage` / `lastDoneDate` / clears `neverDone` |
| `setNeverDone(vehicleId, serviceName, now)` | `neverDone` only |
| `deleteItem(vehicleId, serviceName)` | the delete this DAO has never had |

Each stamps `updatedAt` (the LWW contract), and each **returns the affected row count**.
`upsert`/`insertAll` survive for genuine inserts only, with the same REPLACE warning `VehicleDao`
now carries.

### The no-op guard, as law for this map

Ticket 05's question 6 asked for one rule. **Adopted: every write returns its affected row count,
and zero is an error to surface in words - never a shrug.**

Precedent already set twice today: `setOdometerBaseline` (ticket 13) and
`VehicleDao.applyDecodedIdentity` (the write-back). **From here it is the rule for every write on
this map**, and a new write path that discards its row count is a review failure, not a style
preference.

The read-back in decision 2 is the same rule at the conversational layer: **do not assert an
outcome you have not re-read.**

### `refreshServiceIntervals` is deleted

Question 4 answered: **delete it.** Zero callers, and it does the merge-intervals-onto-existing-rows
job that ticket 14's populate now owns properly, with a diff and a confirmation. Dead code that
looks like a working feature is what made "I changed it to 7,500" plausible in the first place.
Ticket 14 rebuilds the capability; this corpse does not get to sit next to it.

### `LiveToolbox`'s 23 hardcoded successes: scoped, not swept

Question 7. **In scope: the tool wrappers whose underlying write can now fail** - `set_odometer`
(already can, `LiveToolbox.kt:1378`), the new `set_maintenance_interval`, `log_service`,
`log_past_service`. Those must derive `success` from the write rather than asserting it, or the
JSON envelope contradicts the message inside it.

**Out of scope: a 194-call-site sweep of `result(`.** Named here so it is a deliberate boundary and
not an oversight. **Charted as fog** on the map ("the tool envelope's success flag"), to be taken as
its own effort with its own count.

### Ordering, and what this ticket does NOT unblock on its own

- **Ticket 06 must resolve before 05 is built.** Decision 1 is unenforceable without a provenance
  flag, and 06 also owns the Room bump that carries it. Building 05 first means shipping a rule the
  code cannot check.
- 05 resolving still unblocks **07** (hand-add and delete), which unblocks **08**, which with 06
  unblocks **14**.

### Verification

Binding on whoever builds this (L11):

1. **On the device, with Kevin's real data**: change the oil interval from 3,000 to 7,500, force-stop
   the app, reopen, read it off the screen, **then pull the database and confirm the row.** Two
   independent confirmations, because the failure being fixed is a confirmation that lied.
2. **Then say it by voice** and confirm the read-back states 7,500. The spoken path is the one that
   failed originally and it is not verified by the screen working.
3. Log a service against an item whose interval you just edited, and confirm **both** survive - that
   is the read-modify-write race decision 3 removes, and it is invisible unless specifically checked.
4. Confirm a zero-row write is reported. Easiest reachable case: edit an interval on a
   `serviceName` that does not exist.

### Assumptions ledger

- `traced`: the DAO surface and its absence of update/delete; all four write call sites; that
  `insertAll` is `IGNORE` and is the only thing protecting an edited interval today; the 23/194/54
  `success` counts; `AdvisorBriefs.kt:61`'s allowlist; `refreshServiceIntervals` still having zero
  callers; `logServiceDirect`'s read-modify-write shape.
- `reasoned`: that `accept_proposal` satisfies decision 1's confirmation requirement. It follows
  from the rule's intent (no *silent* overwrite) rather than from its literal wording, and it is
  flagged here as a refinement rather than buried.
- **Not built.** Nothing here is implemented.

