# A seeded interval is a guess and has to say so

Type: grilling
Status: open
Blocked by: 01, 02

## Question

The 3,000-mile oil interval is not a hardcoded default and it is not a bug in the arithmetic. **An
LLM produced it**, from a prompt that explicitly asks for the *severe / heavy-duty* schedule:

```
"Use search to find the manufacturer-recommended SEVERE / heavy-duty maintenance schedule ...
 for a ${vehicle.year} ${vehicle.make} ${vehicle.model}. Respond with ONLY a raw JSON array ..."
```
`VehicleController.kt:739-757`, parsed by `parseIntervals` (`:759-781`).

It then renders on screen as `"every 3,000 mi - last at 132,400"` (`ui/fleet/FleetRows.kt:145`),
in exactly the same typography as a number Kevin typed himself. **The app currently cannot tell
Kevin which of its numbers it made up.**

CLAUDE.md §4 rule 5: *anything the document does not state cannot be gated, and must be surfaced as
an estimate, never as fact.* A maintenance interval is not something the car stated. It is the
pantry macro-estimate problem wearing a different hat.

Kevin's ruling at charting: **show it as a guess until I confirm it.**

## What has to be decided

1. **How provenance is stored.** A per-item flag, and what its values are. Two states
   (`SEEDED` / `CONFIRMED`) or three (a hand-typed value is arguably neither)? Note the existing
   vocabulary: `IngestMethod` already has `DETERMINISTIC` / `LLM_RECONCILED` / `UNRECONCILED`, and
   an LLM-guessed interval that reconciles against nothing is closest in spirit to `UNRECONCILED`.
   **Reuse the vocabulary or deliberately don't**, but say which.
2. **The Room change.** `maintenance_items` needs a column. Room is at **v19**. CLAUDE.md §5:
   verbatim generated SQL, additive only, `exportSchema`, no destructive fallback, migration test.
   Check whether this can ride with tickets 07 and 11's schema needs as one bump rather than three.
   **Widening an enum stored as TEXT is not a migration** (§5) - if provenance is TEXT, adding a
   constant later is free.
3. **What existing rows become.** Every row on Kevin's phone was seeded. Does the migration default
   them all to `SEEDED`, or does a row whose `updatedAt` is non-zero count as touched? Ticket 01
   answers whether any row has ever been edited.
4. **The words on screen.** §4 rule 7's discipline: **said in words, never by colour or a glyph
   alone.** What does an unconfirmed row actually read as? `"every 3,000 mi (LEGION's guess)"`?
   A `DeckTag`? Both? It must survive being read aloud too - `get_next_service` and
   `ask_maintenance` speak these numbers, and mission-control's disclosure precedent is that a
   spoken figure carries the same caveat as a rendered one.
5. **What confirming looks like**, and whether editing implies confirming. Editing 3,000 → 7,500
   obviously does. Does tapping "yes that's right" on an unchanged 5,000 also? Is there a bulk
   "accept the whole seeded schedule" affordance, or is that exactly the rubber-stamp this ticket
   exists to prevent?
6. ~~**Should the seed prompt still ask for SEVERE?**~~ **DECIDED 2026-08-15 (Kevin): normal
   schedule, hard-coded. Severe is not offered as a setting.** Ticket 02 established that Schedule A
   is 7,500 mi or 6 months and Schedule B is 3,000 mi with no time interval at all - so the prompt,
   not the model, produced the 3,000. The cost was stated and accepted: a car living a short-trip,
   dusty or towing life has no route back to severe except editing items by hand, which is what
   this ticket's sibling (05) builds. Applied to `lookupServiceIntervals`
   (`VehicleController.kt:740-742`) as a standalone fix; see ticket 14 for the deliberate
   populate flow that replaces automatic seeding.

   **Still open here**, and not settled by that decision: ticket 02 found that some seeded items
   correspond to **nothing in the factory schedule at all** (`Brake Fluid Flush` on the XJ; the XJ
   has no cabin air filter). Those are not severe-vs-normal disagreements - they are inventions,
   and they are the strongest argument this ticket has for a provenance flag.
7. **Should there be an LLM seed at all?** Kevin declined the "stop guessing entirely" option, so
   this is not reopened - but ticket 02 may return a schedule good enough to **bundle as an asset**
   for this specific car, and CLAUDE.md §7 prefers bundled to fetched. A bundled table is
   deterministic; an LLM call is not. Rule on whether a bundled schedule, where one exists, takes
   precedence over the LLM.

## Watch for

`parseIntervals` turns anything `<= 0` or missing into `null`, and `null` renders as
`"no interval on file"` with **no meter drawn at all** (`FleetRows.kt:145,159,193`). So a seed that
half-fails is already invisible rather than wrong. That is the safe direction, but it means
**"the schedule looks fine" and "the schedule is mostly empty" look similar** - check which of the
two Kevin's phone is actually in, using ticket 01's data.
