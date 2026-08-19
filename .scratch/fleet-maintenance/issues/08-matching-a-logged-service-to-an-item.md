---
map: fleet-maintenance
ticket: 08
title: Matching a logged service to the item it satisfies
type: grilling
status: resolved
status-detail: 2026-08-15
blockers: ["07"]
blocked-by: ["[[07-hand-added-items-and-what-delete-means]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Matching a logged service to the item it satisfies

## Question

Kevin's ruling: logging "oil change at 148,200" should reset the Oil Change schedule's clock, **by
matching the service name**. He took this over the pick-from-a-list alternative with the silent-miss
risk stated to him. **That decision is not reopened here.** This ticket exists to make name
matching safe enough that the risk he accepted does not actually bite.

## The mechanism today

`logServiceDirect` (`VehicleController.kt:181-191`) does two writes: it inserts a `ServiceRecord`,
and it upserts the `MaintenanceItem` anchor using a **canonicalised** name. Canonicalisation runs
through `SERVICE_KEYWORDS` (`:71-82`) - **ten entries**. `canonicalizeAndDedupe` (`:684-690`) maps
a free-text name onto a canonical one if a keyword matches.

Composite PK is `(vehicleId, serviceName)`. If canonicalisation returns a name no item uses, the
upsert **creates a new orphan row** rather than failing - a fresh item with an anchor and no
interval, which renders `"no interval on file"` and is invisible on the due list. The clock Kevin
meant to reset never moved, and nothing reported a problem.

That is the silent-miss, concretely. It is also, structurally, the same defect as ticket 05's: a
write that succeeded at the database level while failing at the intent level.

## What has to be decided

1. **What happens when a logged name matches no existing item.** Four candidates:
   - Create the item (today's behaviour, silent)
   - Create it and **say so**: "I've added Transmission Fluid Flush to the schedule as well"
   - Refuse and ask which item was meant
   - Create the `ServiceRecord` regardless, and leave the schedule alone, saying so
   The record itself should probably always be written - it is a true fact about work done - so the
   real question is only what happens to the anchor.
2. **Does the ten-keyword table survive?** Ticket 07 decides whether hand-added names go through
   the canonicaliser. If they do not, the table is matching against a vocabulary Kevin can extend,
   and it must learn: **a hand-added item's name should become matchable**, ideally without Kevin
   editing a Kotlin constant.
3. **Fuzzy or exact?** "Oil & filter" vs "Oil Change" vs "oil change". Case folding is obviously
   fine. Substring matching is what the keyword table already does. Anything beyond that
   (edit distance, an LLM disambiguator) buys accuracy and costs determinism - and CLAUDE.md §4's
   whole posture is deterministic-first where a deterministic path exists.
4. **Confirmation instead of prevention.** Kevin declined the extra tap at log time. But a *read
   back* costs nothing: "logged, and Oil Change is now due at 155,700." If the anchor did not move,
   that sentence cannot be produced - which turns a silent miss into an audible one. **This may be
   the whole answer to the ticket**, and it is the same no-op guard ticket 05 is choosing.
5. **`log_past_service` deliberately writes no `ServiceRecord`** (`VehicleController.kt:199-204`) -
   it backfills an anchor from memory only. Does the new UI have a backfill path, and is the
   distinction between "I did this just now" and "I remember doing this" one Kevin should see?
6. **What the UI log-a-service form does**, given all of the above. If the form is populated from
   the existing item list by default but allows free text, the common case is exact by construction
   and the uncommon case is still expressible - which arguably satisfies both Kevin's choice and the
   risk it carries.

## Verification

On the device: log a service whose name exactly matches an item and confirm the anchor moved. Then
log one whose name matches nothing, and confirm the chosen behaviour from (1) actually happens
**and is visible**. Pull the DB both times. Check `service_records` and `maintenance_items`
together - the bug class here is precisely one where one table looks right on its own.

---

## Answer (2026-08-15)

**Decision (Kevin): record the service, add the missing item, and SAY SO.**

The `ServiceRecord` is always written - work done is a fact regardless of what the schedule knows.
The new schedule row is then **announced**, never created silently:

> "Logged the transmission flush at 227,900. Nothing on your schedule matched, so I've added
> Transmission Flush as an item - it has no interval yet, tell me one when you want it tracked."

**That announcement is the entire fix.** Kevin's `Brake Fluid` and `Brake Pads` rows (ticket 01)
were created by exactly this path, silently, on 2026-08-12, and he learned of them from a database
pull three days later. The behaviour is unchanged; the silence is what goes.

### How matching works, given ticket 07

Ticket 07 demoted `canonicalizeServiceName` from a rewriter to a **comparator**. That applies here
literally:

1. Canonicalise the spoken name and every existing item's name, and compare **case-insensitively**.
2. A match resets that item's anchor via `setAnchor` (ticket 05's targeted write), row count checked.
3. No match creates a new item under the **canonical** form of what was said - and the reply states
   the name it used, so a bad canonicalisation is visible immediately rather than discovered later.

**Deterministic only.** No LLM disambiguation: CLAUDE.md §4 rule 1 is deterministic-first where a
deterministic path exists, and string comparison is one. An LLM here would make the same match
non-reproducible across two identical utterances.

**Fix the titlecase bug first** (found on ticket 07): the fallback at `VehicleController.kt:201`
titlecases only the first character, so `"transfer case fluid"` becomes `"Transfer case fluid"` and
collides with nothing while looking like `"Transfer Case Fluid"`. Proper title case, or this
ticket's "create a new item" path becomes the duplicate generator it is meant to avoid.

### The read-back is already law

Ticket 05 made it so: the reply re-reads the row after writing. `"Oil Change is now due at
235,000"` cannot be produced if the anchor did not move, which turns a silent miss into an audible
one. That is the guard Kevin accepted the name-matching risk against, and it is what makes the risk
survivable.

### `log_past_service` keeps its distinction, and it caused real damage

`logPastServiceDirect` writes **only** an anchor, never a `ServiceRecord` (`:199-204`) - a
remembered approximation does not belong in the precise ledger. Keep that.

But ticket 01 found it doing harm: on 2026-08-12 at 15:50:02 a `log_service` wrote a record at
118,374 **and** the Oil Change anchor; fourteen seconds later a backfill **overwrote that anchor to
118,483 and nulled its date.** A remembered figure silently beat a precise one.

**Rule: a backfill may not overwrite an anchor that a `ServiceRecord` supports without saying so.**
If a precise record exists at or after the backfill's date, the backfill reports the conflict and
leaves the anchor alone. Same shape as the VIN write-back's conflict rule, for the same reason.

### Verification

1. Log a service matching an existing item; confirm the anchor moved **and** the read-back states
   the new due figure. Pull the database.
2. Log one matching nothing; confirm the new item is **announced by name**, and that its name is
   properly title-cased.
3. Backfill over an item that has a `ServiceRecord`; confirm it refuses and says why.
4. Confirm a zero-row write is reported (ticket 05's law).

### Assumptions ledger

- `traced`: `canonicalizeServiceName`'s comparison and titlecase fallback; `logServiceDirect` /
  `logPastServiceDirect`'s split; that only the latter writes an anchor without a date.
- `on-device`: the 118,374 vs 118,483 anchor conflict and its fourteen-second gap, from the
  2026-08-15 pull.
- **Not built.**
