# Populate the schedule from the factory recommendation

Type: prototype
Status: open
Blocked by: 05, 06, 07

## Question

Kevin, 2026-08-15: *"i also want to be able to populate the maintenance list based on factory
recommended intervals. for any car selected either via VIN or manual input."*

Today the only way a schedule gets written is an **automatic** LLM lookup that fires once, at
service start, for any car that is not yet `onboarded` (`onboardPendingVehicles`,
`VehicleController.kt:653-659`). There is no deliberate, user-triggered version, and the automatic
one cannot be re-run usefully because `applyServiceIntervals` writes through
`insertAll` = `@Insert(IGNORE)` (`MaintenanceItemDao.kt:22-23`) - **it can never update an existing
row.**

Ticket 01 showed what that produces in practice: 54 items across 5 cars, **49 with no anchor**,
rampant duplicate concepts from repeated re-seeding under different LLM-chosen names, and on the
Jeep an invented `Brake Fluid Flush` that ticket 02 proved **is not in the factory schedule at all.**

## Decisions already taken (Kevin, 2026-08-15) - not reopened

1. **Diff-and-confirm.** Running a populate **never writes directly.** It produces a screen listing
   what would be added, what would change (current value beside proposed value), and what would be
   left alone. Nothing lands until Kevin picks. This is the answer to "what happens to items already
   on file" and it deliberately costs more to build than either silent alternative.
2. **Normal schedule, hard-coded.** `lookupServiceIntervals`' prompt stops asking for SEVERE.
   Severe is not offered as a setting. **The cost was stated and accepted**: a car living a
   short-trip, dusty or towing life has no route back to the severe figures except editing items by
   hand (ticket 05), which is exactly what that ticket builds.
3. **Manual input takes year, make, model, trim, engine, and mileage.** All six.

## What has to be decided

1. **`engine` is not a column.** `Vehicle` has `year/make/model/trim` but no engine
   (`data/local/Vehicle.kt`). Kevin wants it, and ticket 02 showed why it matters - a 4.0L XJ and a
   2.5L XJ differ on plugs and capacities. **This is a Room bump, v19 -> v20**, additive, one
   `TEXT NOT NULL DEFAULT ''` column. CLAUDE.md §5: verbatim generated SQL, `exportSchema`, schema
   JSON committed, no destructive fallback, migration test. Confirm the column's `createSql` in
   `app/schemas/` afterwards rather than assuming.
2. **Mileage at registration.** Kevin asked for it, and it closes the exact hole that broke his Jeep
   - a freshly-registered car computing due dates against odometer zero. It writes
   `odometerBaseline`/`odometerBaselineAt`. **Interacts directly with ticket 10**, which owns the
   odometer entry surface; decide whether this form reuses that control or duplicates it. Duplicating
   it would be two places to get the estimate-labelling wrong.
3. **What the diff actually compares.** Three-way, not two: the factory proposal, the current row,
   and **who authored the current row**. Ticket 06's provenance flag is what makes
   "you set this to 7,500" render differently from "LEGION guessed 3,000" - **without it the diff
   cannot tell Kevin which of his own numbers are at risk**, which is the whole point of showing him
   one. That is why this ticket is blocked by 06.
4. **What the diff does about rows the factory does not name.** Ticket 02 found `Brake Fluid Flush`
   is not in the XJ schedule and the XJ **has no cabin air filter** at all. So a populate will
   routinely produce "on file, not in the factory schedule" rows. Are they flagged for deletion,
   left alone, or shown as a third category? An invented row and a hand-added row look identical
   here unless provenance distinguishes them.
5. **Name matching across 26 factory strings and 10 canonical keywords.** Ticket 02 counted **26
   distinct factory service names** for one vehicle against `SERVICE_KEYWORDS`' ten
   (`VehicleController.kt:71-82`). The diff has to decide whether a proposed "Drain and refill front
   and rear axles" is the same item as an existing "Differential Fluid Service" - **and getting that
   wrong is what produced the duplicate-concept mess ticket 01 measured.** Ticket 07 owns whether
   hand-added names go through the canonicaliser; this ticket cannot answer independently.
6. **Deterministic vs LLM, and whether anything gets bundled.** CLAUDE.md §4 rule 1 is
   deterministic-first *where a deterministic path exists*. For an arbitrary car there is none, so
   the LLM lookup stands and its output is **an estimate under ticket 06's labelling** - a factory
   figure retrieved by a model is still not a figure the car stated. **But ticket 02 already
   produced a real, sourced 1998 XJ schedule.** §7 prefers bundled assets to runtime fetches.
   Rule on whether a bundled table, where one exists for a specific car, takes precedence over the
   lookup - and if so, where it lives and how it is kept honest.
7. **The VIN path.** Kevin wants "selected either via VIN or manual input", but **the VIN decode
   does not currently write back to `vehicles` at all** - that is ticket 04's open item and ticket
   13's question 4. Decide whether this ticket's VIN path *is* that write-back, or consumes it.
   Note what the decode does and does not give: `vehicle_specs` has cylinders, displacement, engine
   config, manufacturer and body class, **but no `year`, `make` or `model` field** - those come from
   the VIN itself, and the year is the VIN's 10th character. Confirm what `VinDecoder` actually
   parses before designing around it.
8. **Where the trigger lives.** A button on the specs screen next to the VIN actions, a row on the
   rebuilt maintenance surface (ticket 09), or both. Also: does the **automatic** first-run seed
   survive at all once a deliberate one exists, or does a new car simply start empty and wait to be
   populated? Leaving both means two paths that can disagree.

## Watch for

**Re-running a populate is precisely the operation that made the mess.** Every duplicate-concept
row ticket 01 found - `Air Filter` / `Air Filter Replacement` / `Engine Air Filter`, `Axle Fluid` /
`Axle Lubricant` / `Axle Lubricant Service` - was created by a re-seed under a slightly different
LLM-chosen name. This ticket makes re-running easy and deliberate. **If the matching in question 5
is not solid, this feature multiplies the existing defect rather than fixing it.**

## Verification

On the device, on Kevin's real Jeep: run a populate, confirm the diff shows the oil interval moving
3,000 -> 7,500 and flags `Brake Fluid Flush` as not-in-schedule, confirm nothing writes until
accepted, accept it, force-stop, reopen, and pull the DB to confirm the rows. **The service anchors
at 118,483 must survive untouched** - that is the check that matters most, because losing them is
the failure this map already suffered once.
