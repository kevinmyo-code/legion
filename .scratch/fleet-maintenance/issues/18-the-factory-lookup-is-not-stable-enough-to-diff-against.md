# The factory lookup is not stable enough to diff against

Type: grilling
Status: open

## Question

Ticket 14's populate assumes the factory schedule is a **stable fact** the driver's own schedule can
be diffed against. Four consecutive lookups on Kevin's 1998 Jeep Cherokee, 2026-08-15 between 22:51
and 23:04, show it is not. Same car, same prompt, minutes apart.

**Availability.** Three of the four came back with an empty list (the model's "I cannot find the
schedule" signal). Ticket 17 now refuses those, so they surface as a retryable error rather than a
diff - but the driver has to tap TRY AGAIN roughly every other attempt.

**Content.** The two that DID return disagreed with each other on three of eight items:

| Item | Run 1 (22:59) | Run 2 (23:04) |
|---|---|---|
| Spark Plug Replacement | factory says 30,000 mi **or 24 mo** | **not in the factory schedule** |
| Air filter | factory says "Air Cleaner Filter", 30,000 mi or 24 mo | **not in the factory schedule** |
| Tire Rotation | **not in the factory schedule** | factory says 7,500 mi or 6 mo |

Only Oil Change (7,500 mi / 6 mo, matching ticket 02's read of Chrysler Schedule A) was stable across
both.

## Why this matters more than it looks

**Every category of the diff inverts, not just the values.**

- `notInFactorySchedule` is presented to the driver with a DELETE button and, for a seeded row, the
  words *"LEGION guessed this item, and the factory schedule doesn't list it - looks invented."*
  Run 2 says that about spark plugs. Spark plugs are not invented.
- `wouldChange` writes `CONFIRMED`. During this very session, run 1's spark plug proposal (24 mo) was
  accepted and is now stored as `CONFIRMED` - a tag meaning *the driver named this value* - while the
  next lookup denies the factory lists the item at all. **A flaky retrieval has been laundered into
  the strongest provenance the schema has.** That is CLAUDE.md §4 rule 5's exact failure: an estimate
  reading as a fact one layer downstream.
- A driver who taps TRY AGAIN until they get an answer is sampling until they get *an* answer, not
  converging on *the* answer. The retry loop makes the instability invisible.

Ticket 17 fixed the case where the lookup says nothing. This is the case where it says something
different each time, and the second is the harder one, because it has no tell.

## What this does NOT undermine

Ticket 14's own acceptance bar - **run the populate twice, confirm it does not multiply rows** - was
met on-device and should not be re-litigated by this ticket. Across both runs the schedule stayed at
8 active rows, the "Air Cleaner Filter" near-miss merged onto the existing `Air Filter Replacement`
row rather than creating a twin, and the two tombstoned rows were not resurrected. The diff/accept
machinery is sound. **The problem is the quality of its input, not its logic.**

## Options, none chosen (this is Kevin's call)

1. **Ground the lookup in something citable.** Require the model to return a source per item and show
   it; drop items with no source. Costs a prompt round and may make the empty case more common.
2. **Sample the lookup N times and keep only what agrees.** Turns instability into a confidence
   signal instead of hiding it. Costs N calls per populate on Kevin's own key.
3. **Never let a lookup write `CONFIRMED`.** Add a provenance value for "accepted from a factory
   lookup" so it is distinguishable from a value the driver actually typed. Cheapest, and it is the
   §4-rule-5-shaped fix - it stops the laundering without pretending the lookup got better.
4. **Retire `notInFactorySchedule`'s delete affordance**, or soften it to "the lookup didn't mention
   this", since a single flaky sample is thin grounds for suggesting a driver delete a real service.
5. **Drop the automatic lookup for pre-OBD-II-era vehicles** and treat manual entry as the primary
   path for a 28-year-old car, where the published schedule is least likely to be retrievable.

3 and 4 are cheap, independent of each other, and do not depend on the lookup improving. 1 and 2 are
attempts to actually fix the input. They are not mutually exclusive.

## Immediate consequence, whatever is chosen

`Spark Plug Replacement = 30,000 mi / 24 mo, CONFIRMED` on Kevin's Jeep came from run 1 and is
contradicted by run 2. It is currently indistinguishable from a value he typed. It should be reviewed
by hand, and it is the concrete example to reason about when picking from the list above.

## Assumptions ledger

- `on-device`: all four lookups, screenshotted; the three-item contradiction table read directly off
  run 1 and run 2's screens.
- `on-device`: the post-accept database state (8 active rows, no duplicate air-filter row, Oil Change
  7,500/6, Spark Plug 30,000/24, all `CONFIRMED`), read from a pulled `legion_database` with its WAL,
  file sizes matched against the device.
- `reasoned`: that the empty responses are the model's instructed "cannot find" signal rather than
  some other empty-producing path - `parseIntervals` returns `null`, not empty, on both the throw and
  the unparseable branch, so a non-null empty list can only be a well-formed `[]`. The response body
  itself was never captured (no LEGION tags reach logcat on the A25).
- `reasoned`, NOT verified: that search grounding is functioning at all on these calls. Never
  confirmed either way; it is one candidate explanation for the flakiness among several.
- Nothing here is built. This ticket decides, it does not implement.
