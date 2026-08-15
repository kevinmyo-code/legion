# Matching a logged service to the item it satisfies

Type: grilling
Status: open
Blocked by: 07   # resolved 2026-08-15 - UNBLOCKED

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
