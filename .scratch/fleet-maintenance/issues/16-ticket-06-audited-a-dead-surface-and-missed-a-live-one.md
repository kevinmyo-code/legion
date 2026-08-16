# Ticket 06 audited a dead surface and missed the live one behind it

Type: task
Status: open

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
