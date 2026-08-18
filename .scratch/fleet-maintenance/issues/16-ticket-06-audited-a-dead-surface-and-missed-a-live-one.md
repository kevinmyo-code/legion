---
map: fleet-maintenance
ticket: 16
title: Ticket 06 audited a dead surface and missed the live one behind it
type: task
status: resolved
status-detail: 2026-08-15
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Ticket 06 audited a dead surface and missed the live one behind it

## Question

Surfaced by ticket 15's re-audit, 2026-08-15. Ticket 06 required the seeded-interval `[GUESS]`
disclosure on **six** surfaces that render or speak an interval. Re-checking that list found it was
wrong in both directions.

| Ticket 06's surface | Reality |
|---|---|
| #3 `CarToolbelt.maintenanceSchedule` | **DEAD CODE.** No caller anywhere in the tree. `forMaintenance`'s own comment says why: *"MaintenanceAgent pre-seeds the schedule into its context, so the belt omits get_maintenance_schedule"* |
| **`MaintenanceAgent.describeItem`** | **The actual live formatter** that builds that pre-seeded prompt. **Ticket 06 never named it.** Carries no disclosure - reads raw `intervalMiles`/`intervalMonths` with no `intervalSource` check |
| #2 `VehicleController.nextService` / `formatRemaining` | Spoken. Carries **no** disclosure |
| #6 `FleetPlaybook` | Static prompt constant with its own generic *"every recommendation is an ESTIMATE"* framing. Arguably compliant, but not via `isGuessTag` |
| #1 `FleetRows`, #4 `FleetDrilldowns` | Built (ticket 09) |
| #5 `FleetDigestBuilder` | Built (ticket 15) |

So the audit **counted a function nobody calls and missed the one that actually feeds the model.**

## Why this is the same mistake twice

Ticket 06 found its six by grepping `intervalMiles|intervalMonths`. `FleetDigestBuilder` was missed
because it consumes an already-formatted string (ticket 15 fixed that). **`CarToolbelt.maintenanceSchedule`
was counted because it greps positive - it matches the pattern perfectly and is simply never called.**

A grep proves a surface *exists*. It says nothing about whether it is **reachable**, and nothing
about whether something else reachable does the same job. Both failure directions bit the same
ticket.

## What to do

1. **Add the disclosure to `MaintenanceAgent.describeItem`.** This is the priority: it is
   model-facing and it is ticket 06's own stated harm - *"feeding an unlabelled guess into a model
   that states it back confidently is how an estimate launders itself into a fact."*
2. **Add it to `VehicleController.nextService`/`formatRemaining`.** Spoken, and ticket 06 explicitly
   required the caveat carry aloud because a tag cannot be heard.
3. **Decide on `FleetPlaybook`.** Its blanket "everything here is an estimate" may be enough, or it
   may need the per-item flag. Rule explicitly rather than leaving it ambiguous.
4. **Delete `CarToolbelt.maintenanceSchedule`, or wire it up.** Dead code that looks like a live
   feature is what ticket 05 deleted `refreshServiceIntervals` for, and it has now actively misled
   an audit. Same disease, same treatment.
5. **Re-run the audit a third time, by reachability rather than by pattern.** For each candidate,
   establish it has a live caller before counting it.

## Why ticket 15 did not just fix it

Correctly. Its scope was `FleetDigestBuilder`'s lines; this is two more model-facing prompt surfaces
plus a spoken one. CLAUDE.md §8 makes an unanswered fork a stop-and-surface, not an improvise. It
stopped and said so, which is the behaviour that found this at all.

## Assumptions ledger

- `traced` (ticket 15's agent): `CarToolbelt.maintenanceSchedule` has no caller (grep, plus its
  sibling comment); `MaintenanceAgent.describeItem` carries no `intervalSource` check;
  `nextService`/`formatRemaining` carry no guess wording; the word "guess" appears only in
  `FleetRows`/`FleetDrilldowns`/`MaintenanceWrites`.
- `reasoned`: that tile-caption consumers of `DueRowView` (`buildFleetTile` and friends) are outside
  ticket 06's concern because no model ever sees a tile string. Consistent with ticket 06's own
  framing, not independently ruled.
- **Not built, not on-device.**

## Resolution (built 2026-08-15, commit `a27e33a`)

All five steps, plus one thing the ticket did not name.

1. **`MaintenanceAgent.describeItem` carries the disclosure**, in full words, whenever the interval
   is unconfirmed: `(LEGION's guess, unconfirmed by the driver)` or `(from a factory lookup,
   unconfirmed by the driver)`. A `CONFIRMED` item gets no suffix, and an item with no interval still
   reads "no interval on file" with none either - there is no number there to doubt.
2. **The spoken path carries it too.** `ServiceCandidate` gained `isGuess`, populated in
   `computeNextService`. That is the seam that matters: the flag travels WITH the candidate, so a
   consumer cannot render the name and timing without the disclosure to hand. Carried into
   `LiveToolbox.getNextService` (spoken, phrased for speech rather than as a bracketed tag),
   `FleetDigestBuilder.nextServiceLine`, `HomeDigestBuilder.fleetHeadline`, and
   `CarAspectSummaries.fleet`. **`formatRemaining` cannot carry it and does not try** - it takes only
   `(remaining: Long, unit: ScheduleUnit)` and never sees the item, so the ticket naming it was
   simply wrong about where the seam is.
3. **`FleetPlaybook` ruled: the blanket framing is NOT sufficient, and one clause was affirmatively
   wrong.** Section 0's "every recommendation is an ESTIMATE" governs the MODEL's recommendations,
   not an on-file interval the app hands it. And the baselines section told the model that where an
   item carries an interval, "that interval was chosen for this vehicle, use it" - false for a
   `SEEDED` row, and precisely how a 3,000-mile oil interval became authoritative on Kevin's Jeep
   (ticket 01). Corrected, plus one `REASON FROM THE LOG` bullet on reading the new suffix.
4. **`CarToolbelt.maintenanceSchedule` deleted**, zero callers reconfirmed across main and test.
   Tombstoned in the same style as `refreshServiceIntervals`, which went for the same reason.
5. **Audit re-run by reachability.** Every consumer of `NextService.byMiles`/`byTime` enumerated by
   grep and each one checked for a live caller before counting it: the four surfaces above plus
   `LiveToolbox`. `HomeDigestBuilder.fleetHeadline`'s overdue branch names no interval or timing
   figure and was deliberately left alone.

**Not named by the ticket, found while reviewing the build:** `ItemDetailScreen`'s `[GUESS]` banner
hardcoded *"LEGION guessed this interval - you haven't confirmed it."* for any unconfirmed row. Once
ticket 18 widened `isGuessTag` past `SEEDED`, that sentence started being said about `LOOKUP` rows,
where it is false - a factory-lookup value the driver reviewed was never LEGION's guess. Ticket 18
fixed this laundering in one direction; this was the same lie told the other way round. Fixed with
the same split used for the CONFIRM ALL dialog: the tag stays coarse, the sentence is precise.

**Structural fix underneath all of it:** the predicate moved from `ui/fleet/FleetRows.kt` onto
`MaintenanceItem` itself (`intervalIsUnconfirmed`, `provenanceWords`). `vehicle/` and `advisor/` both
needed it, and `FleetRows` already imports `VehicleController`, so importing back would have inverted
the dependency. `isGuessTag` and friends now delegate, keeping every UI call site unchanged. One rule,
one definition - which is the actual defence against a fourth pass of this same ticket.

Tests 1316 -> 1334.

## Verification not performed

**No on-device check.** The A25 dropped off wireless adb before this build could be installed, and
only the retired A17k was reachable, which is never written to. Recorded as a gate rather than a
footnote (CLAUDE.md L11), since that discipline is the reason this ticket exists.

Two further notes, both honest limits rather than oversights:
- The `LOOKUP` branch of the item-detail banner needs a `LOOKUP` row to exist, which means accepting
  a populate proposal into Kevin's real schedule. He asked to review those values himself, so it was
  not created for a screenshot.
- `FleetPlaybook.TEXT` grew 9,065 -> 9,573 chars, inside the 10,000-char ceiling
  `PlaybookKeywordsTest` enforces, but this was NOT re-measured with the real `countTokens`
  tokenizer. Noted in the file's own KDoc; re-measure before the next addition.
