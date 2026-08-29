---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The OBD cursor halts forever on a vehicle that can never resolve

**Found 2026-08-29 on the first real run, against real data. 12,807 of 26,059 samples uploaded, then
it stopped and cannot resume.**

## What happened

`ObdSampleReconcile` uploaded 12,807 samples (2026-07-16 to 08-11, all three real cars) and then
reported honestly:

> Stopped early: `66:1E:11:0E:82:0E`: vehicle not yet migrated - run Fleet's vehicle upload first.
> Samples past this point were not checked this run.

**That advice cannot be followed.** `66:1E:11:0E:82:0E` is one of the two **year-0 placeholder
vehicles** - the same rows that aborted the fleet projection on 2026-08-28 and that
`skippedUnexportable` now deliberately refuses to upload, because `public.vehicles` requires
`year between 1885 and 2200` and these carry 0 with no name, make or model.

So the cursor is waiting for something that will never happen. Running Fleet's vehicle upload will
skip that car by design, every time.

## Why it was built this way, and why that reasoning was right

The halt is deliberate. Its own doc says the cursor must never advance past a sample whose parent it
could not resolve honestly, because advancing would silently strand those samples forever - the
cursor would move on and nothing would ever come back for them. Skipping-and-continuing trades a
visible stall for invisible data loss, and the visible stall is the better failure.

**The reasoning holds. The gap is that it assumed every unresolved parent is TEMPORARY** - a vehicle
not yet migrated, which a later run fixes. A vehicle that is *permanently* unexportable was not
considered, and the fleet cutover created exactly that category one day earlier.

## Options

1. **Skip samples whose vehicle is permanently unexportable, and name them.** The reconcile already
   knows which vehicles `skippedUnexportable` refused and why. A sample whose parent is on that list
   is not "not yet migrated", it is "never will be" - a different sentence, and one the cursor may
   safely advance past because no future run would resolve it either. Report the count in words.
2. **Two cursors** - a high-water mark plus a set of known-skipped ids. More faithful, more
   bookkeeping, and it only matters if a permanently-dead vehicle can later come alive.
3. **Delete the placeholder vehicles.** They are `default` and an OBD-MAC row with no name, make,
   model or year, and 13,252 samples hang off them. **Not recommended without knowing what those
   samples are** - they may be real readings taken before a car was properly identified, in which
   case the fix is to give the vehicle a year, not to delete it.

## The numbers, which make the shape of it obvious

| vehicle | samples | status |
|---|---|---|
| `00:1D:A5:0E:82:0E` (Jeep) | 9,863 | real |
| `car:c352c532-...` (F-150) | 5,691 | real |
| `imported-mitsubishi-outlander-2020` | 5,242 | real |
| **`default`** | **5,242** | **permanently unexportable** |
| **`66:1E:11:0E:82:0E`** | **21** | **permanently unexportable, and what stopped the run** |

**20,796 samples belong to real cars; 5,263 belong to the two dead rows.** 12,807 are already up, so
**7,989 real samples are stuck behind 5,263 dead ones** - and the row that actually halted the cursor
carries twenty-one.

That is the cost of the conservative halt stated exactly: a fifth of the data is blocked by junk, and
the blocking junk is 0.08% of the table.

**Recommendation: option 1**, plus a look at what those 13,252 samples actually are before anything
is deleted. The distinction option 1 draws - not-yet-migrated versus never-will-be - is the honest
one, and it is already computable from data the reconcile has in hand.

## What this is NOT

Not data loss. Every sample is still on the phone; 12,807 are now also on the server. Nothing was
written wrongly and the run reported its own stopping point precisely.

**And it is the argument for running things.** 2,777 tests pass. This needed one real run against
one real phone with two junk rows in it.
