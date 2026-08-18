---
map: fleet-maintenance
ticket: 17
title: "An empty factory list is a failed lookup, and the guard only covered half of it"
type: task
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# An empty factory list is a failed lookup, and the guard only covered half of it

## Question

Found on-device 2026-08-15, on the **first real populate ever run against Kevin's Jeep** - the run
ticket 14 named as its own acceptance bar. The diff came back as a single section:

```
POPULATE SCHEDULE
NOT IN THE FACTORY SCHEDULE (8)
  AIR FILTER REPLACEME...   every 30,000 mi or 30 mo
  BRAKE FLUID FLUSH         every 24 mo
  COOLANT FLUSH             every 30,000 mi or 24 mo
  DIFFERENTIAL FLUID SERVICE every 30,000 mi
  OIL CHANGE                (in the scroll gap)
  SPARK PLUG REPLACEMENT    every 30,000 mi
  TIRE ROTATION             every 6,000 mi or 6 mo
  TRANSMISSION FLUID SERVICE every 30,000 mi
```

Eight of eight live items, each captioned *"The factory schedule doesn't list it."* Zero would-add,
zero would-change, zero possible-match, zero would-restore.

**Oil change is in that list.** No factory schedule for a 1998 Cherokee omits oil changes, so the
screen is not reporting a fact about the car - it is reporting a failed lookup as one.

## Root cause

`fetchFactorySchedule` returned an **empty but non-null** list, and `buildPopulateDiff` accepted it.
With zero factory items the `for` loop never runs, `matchedActiveNames` stays empty, and
`notInFactorySchedule = active.filterNot { it.serviceName in matchedActiveNames }` therefore returns
**every active row**. The screen then renders that as a confident claim.

The bitter part is that this was already known and already written down. `PopulateSchedule.kt`'s own
doc comment, added by ticket 14's review, said:

> Returns `null` on a genuine lookup failure ... **never a diff built from an empty list standing in
> for one.** ... an empty factory schedule and a failed network call must never collapse onto the
> same "everything on file is not-in-schedule" diff.

And `VehicleController.lookupServiceIntervals`' doc spelled out the harm almost exactly as it
occurred: *"every active item on file would then show as `notInFactorySchedule`, a network hiccup
dressed up as 'delete everything.'"*

The guard that followed all that prose was `?: return null` - which catches `null` and nothing else.

**And the prompt aims straight at the gap.** `lookupServiceIntervals`' prompt ends:

> Return an empty array if you cannot find the schedule.

So the model's own not-found signal is `[]`, the single value nothing checked. This is not an exotic
failure path; it is the documented, instructed one.

## Why the tests did not catch it

Three of `PopulateScheduleTest`'s eleven tests passed `factoryItems = emptyList()` **as a shortcut**
to force every existing row into `notInFactorySchedule`. They asserted a sensible-looking answer for
the exact input that is a failed lookup. The suite did not miss the case - it **blessed** it.

That is a sharper variant of L10 ("a grep-clean result is not a done result"): a test that exercises
a bad input and asserts a plausible output is worse than no test, because it converts an unguarded
hole into a documented behaviour. All three were rewritten to carry a non-empty, agreeing factory
list, with a comment saying why.

## Resolution

1. **The refusal lives in `buildPopulateDiff`**, not at the caller: `if (factoryItems.isEmpty())
   return null`, return type now `PopulateDiff?`. Put in the pure builder because that is where a
   unit test can pin it, and because keeping the invariant next to the logic whose meaning it
   protects is what stops the two collapsing again at some third layer. `loadPopulateDiff` needed no
   body change - it already returned `PopulateDiff?` and now propagates both failure shapes.
2. **There is no vehicle whose true factory schedule is zero items.** So empty is never a fact about
   the car and never needs to be representable. No information is lost by refusing it.
3. **The error copy names both causes.** It read *"Couldn't reach the factory-schedule lookup. Check
   the connection and try again."* - which would have been a lie on the empty branch. Now: *"Couldn't
   get a factory schedule for this car - either the lookup was unreachable or it came back empty.
   Nothing has been changed. Try again."* The "nothing has been changed" clause is load-bearing: the
   screen it replaces was proposing that the driver delete their whole schedule.
4. **New test** `an empty factory list is refused outright, never rendered as everything-not-in-schedule`,
   asserting `null` for three existing-side shapes (real schedule, empty, tombstoned-only) - the
   emptiness of the FACTORY side decides it, and no existing-side shape may talk it back into a diff.

## What this does NOT resolve

**Why the lookup came back empty is still unknown.** Logcat on the A25 shows framework logs but no
LEGION `Log.w` tags, so neither "Service interval lookup failed" nor "Failed to parse service
intervals" could be read back, and no branch could be confirmed from logs. Since `parseIntervals`
returns `null` on anything unparseable and `null` on a thrown call, an empty non-null list can ONLY
be a well-formed `[]` from the model - so the model did answer, and answered "I can't find it."
Whether that is search grounding being unavailable, the prompt being too strict ("Include ONLY items
the manufacturer actually publishes"), or a genuine retrieval miss on a 28-year-old vehicle is open.

**Update, same session, after the fix shipped.** Four lookups were run in all. The empty result is
**intermittent, not deterministic**: three came back empty, two returned a real schedule (one after a
TRY AGAIN). So this is a flaky retrieval, which is what makes the guard load-bearing rather than
cosmetic - without it roughly every other populate proposes deleting the driver's whole schedule. The
guard was then watched catching a genuinely empty lookup on the phone, showing the retry screen
instead of a diff.

Ticket 14's acceptance bar - **run the populate twice, and confirm re-running does not multiply
rows** - was **met**: across the two successful runs the schedule stayed at 8 active rows, the "Air
Cleaner Filter" near-miss merged onto the existing `Air Filter Replacement` row instead of creating a
twin, accepted items dropped out of the second diff, and the two tombstoned rows were not
resurrected. Verified in the pulled database, not just on screen.

**What the four runs exposed instead is a different and larger problem: the two successful lookups
disagreed with each other on three of eight items.** That is `18-the-factory-lookup-is-not-stable-enough-to-diff-against.md`,
which is open and is Kevin's call. This ticket only ensures a failed lookup can never again present
itself as a schedule; it does nothing about a lookup that succeeds and says something different each
time.

## Assumptions ledger

- `on-device`: the eight-item all-not-in-schedule diff, screenshotted on the A25 (Samsung SM-A256U),
  app build `c0c9a394…`, 2026-08-15 22:51.
- `traced`: the empty-list path through `buildPopulateDiff` (loop never runs → `matchedActiveNames`
  empty → every active row filtered into `notInFactorySchedule`); `parseIntervals` returning `null`
  on both the throw and the unparseable branch, so empty-non-null implies a well-formed `[]`.
- `tested`: the new refusal test and the three rewritten ones pass; full suite re-run after the
  change.
- `reasoned`, NOT verified: that the model returned `[]` because it could not find the schedule, as
  its prompt instructs. The response body was not captured - see "What this does NOT resolve".
- `reasoned`: that the "Oil Change" hidden in the scroll gap is the eighth item. Alphabetical
  ordering places it between "Differential Fluid Service" and "Spark Plug Replacement", and it is
  the only live item unaccounted for. Not read directly off the screen.
