# `isDue` and the fleet digest inherit two gaps this map closed elsewhere

Type: task
Status: resolved (2026-08-15)

## Question

Surfaced by senior-dev review of ticket 09's screen build, 2026-08-15. Both are **pre-existing code
that ticket 09 did not touch**, and both were deliberately left out of that diff rather than fixed
in passing - changing them means changing a contract several unrelated callers depend on.

### 1. `VehicleController.isDue` has no odometer-unset guard

Ticket 09 fixed the "due in 121,450 miles" absurdity in the **render** path (`chooseDueAxis` refuses
the miles axis when the driver has never confirmed an odometer). `isDue` computes the same thing
independently:

```kotlin
val mileageDue = item.intervalMiles != null && item.lastDoneMileage != null &&
    currentMileage - item.lastDoneMileage >= item.intervalMiles
```

No guard. And `isDue` is what sorts an item into OVERDUE vs UPCOMING, so with `odometerBaseline == 0`
and any accumulated trip miles, **an item can be pushed into OVERDUE off an odometer nobody
confirmed.** `toDueRow`'s `overdue -> "OVERDUE"` branch never re-checks the axis once `overdue` is
already true; it just prints the word.

So the render path is honest about the number and the sort order is not. **The row would say
"OVERDUE" and "odometer not set" at the same time.**

Why it was not fixed in ticket 09: `isDue`'s contract is depended on by the digest builders,
`nextService`, and the advisor surfaces. Changing it is its own change with its own blast radius.

### 2. `FleetDigestBuilder`'s maintenance lines carry no `[GUESS]`

Ticket 06 audited **six** surfaces that render or speak an interval and required the guess
disclosure on all of them. `advisor/digest/FleetDigestBuilder.kt` was **not one of the six** - it
was missed at audit time, and it feeds `AdvisorBriefs` alongside `FleetPlaybook`'s output.

That matters for the reason ticket 06 gave for including the two prompts in the first place:
**feeding an unlabelled guess into a model that then states it back confidently is how an estimate
launders itself into a fact.** A disclosure that reaches five of six model-facing surfaces is not a
disclosure.

Ticket 09 touched this file, but only mechanically, to track the new `DueRowView.sub` format.

## What to do

1. Decide whether `isDue` takes an `odometerUnset` parameter (mirroring what ticket 09 threaded
   through `FleetRows`), or whether the guard belongs at its callers. **Count the callers first** -
   this map's standing rule, and it has reframed four tickets already.
2. Make it impossible for a row to read `OVERDUE` and `odometer not set` simultaneously, whichever
   way (1) is resolved.
3. Add the `[GUESS]` disclosure to `FleetDigestBuilder`'s maintenance lines, and **re-run ticket
   06's audit** to confirm six was the whole set and no seventh surface was missed the same way.

## Watch for

Ticket 06's audit found six surfaces by grepping `intervalMiles|intervalMonths`. `FleetDigestBuilder`
consumes an already-formatted `DueRowView.sub` instead, so it never matched. **Any re-audit must
follow the formatted strings as well as the raw fields**, or it will miss the same class of
consumer again.

## Assumptions ledger

- `traced` (by the reviewing agent): `isDue`'s missing guard and its use by `buildScheduleRows`;
  `toDueRow`'s `overdue` short-circuit; `FleetDigestBuilder` consuming `row.sub` and feeding
  `AdvisorBriefs`; that ticket 06's six-surface list did not include it.
- `reasoned`: that trip-mile accumulation against a zero baseline is reachable. The recorder's
  accumulation gate is unconditional on `odometerBaseline`, but it has never actually moved off zero
  on Kevin's Jeep (ticket 01 measured 938 speed samples and an accumulator of 0.0, for a separate
  latch defect ticket 10 owns). **So this is currently latent, not live.**
- **Not built, not on-device.**

## Closed 2026-08-15

Both gaps were built the same day the ticket was filed; the status line above was simply left stale
and is corrected here at effort close. Verified by reading the code, not by trusting the note:
`VehicleController.isDue` takes `odometerUnset` and guards the mileage axis with it, matching
`ui.fleet.chooseDueAxis`; `FleetDigestBuilder` renders `"$phrase - guess, unconfirmed"` off
`row.isGuess`, and its doc quotes `isGuessTag`'s rule (now `!= "CONFIRMED"`, widened by ticket 18).
