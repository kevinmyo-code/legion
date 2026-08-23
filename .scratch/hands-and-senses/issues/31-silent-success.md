---
map: hands-and-senses
ticket: "31"
title: "It did the thing and did not say so"
type: bug
status: built
status-detail: "Fixed: the form repaints from what was written, a dated past-service log writes a real record, and a spoken name matches an existing item or asks. Kevin's brake rows untouched. Owes the on-phone replay."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# It did the thing and did not say so

Kevin, 2026-08-22, twice: *"i typed 227483 and tried to save anchor but nothing happened"* and
*"i tried to log date for brake fluid flush by saying it was done at the same time as the oil
change. but it didnt register too."*

**Both writes succeeded.** From his own conversation audit and database:

```
22:38:37  tool_result log_past_service {"success":true,"message":"Got it, filed Brake Fluid Flush into the record."}
22:38:26  tool_result ask_fleet      {"success":true,"message":"The last brake fluid flush ... was logged at 227,483 miles."}
```
`maintenance_items`: `Brake Fluid Flush  mileage=227374  date=NULL  deleted=0`

The app did what he asked and showed him nothing that said so. **A silent success is worse than a
visible failure**: a failure he can retry, this he can only distrust.

## Three defects

### 1. The form echoes what was typed, not what was written

`ui/fleet/FleetDrilldowns.kt` (~:772-778) repaints its local state from the FORM's raw values after
a successful save:
```kotlin
current = target.copy(
    lastDoneMileage = if (mode == DONE_AT) mileage else null,
    lastDoneDate = if (mode == DONE_AT) date else null,
)
```
Since `MaintenanceWrites.writeSetAnchor` now RESOLVES the date through the service records
(ticket 28), the write and the echo can differ by construction. A mileage-only save writes a
resolved date and paints back null - identical on screen to nothing happening.

**Fix:** the write returns what it actually wrote, and the screen renders THAT. Not the form's
guess, not a re-read that races. Same disease as 28/29 - one fact, two sources - one layer up.

### 2. A voice "same time as the oil change" writes an anchor and no record

`log_past_service` moved the maintenance clock to 227,374 and created no `service_records` row, so
the flush has a mileage and still no date, and Service History shows nothing. Ticket 07 fixed this
for the hands path; the voice path still writes only the anchor.

**Fix:** a past-service log that carries a real date writes a real record, through the same path
`log_service` uses. If it genuinely has no date, it must not claim one.

### 3. Near-duplicate service names breed

The Jeep now holds `Brake Fluid` (deleted), `Brake Fluid Flush` (live), `Brake Pads` (deleted).
Voice created a NEW item rather than matching the existing one. Left alone, the fleet accumulates
synonyms and each holds a fragment of the truth.

**Fix:** match a spoken service against existing items for that vehicle before creating one -
case-insensitive, and tolerant of the obvious suffix pair (`Brake Fluid` vs `Brake Fluid Flush`).
**When ambiguous, ASK rather than guess** - the same posture `place_call` takes on two contacts.
Creating a new item stays legal when there is genuinely no match.

## Rules

- Nothing may report a write it did not make (CLAUDE.md §7), and nothing may stay silent about a
  write it DID make - that is this ticket's whole point.
- **Do not merge or delete Kevin's existing three brake items.** Propose what a cleanup would take;
  he decides. Soft-deleted rows stay deleted.

## Verification

- Suite green both ways, one run fresh.
- Tests: a mileage-only save renders the RESOLVED date; a dated past-service log writes a record;
  a spoken name matching an existing item does not create a second one; an ambiguous name asks.
- On the phone: save an anchor and SEE it; say "brake fluid flush was done with the oil change" and
  find it in Service History with the date.
