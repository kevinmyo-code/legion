# Populate the schedule from the factory recommendation

Type: prototype
Status: resolved (2026-08-15)
Blocked by: 05, 06, 07   # all resolved 2026-08-15 - UNBLOCKED

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

---

## Answer (2026-08-15)

### The automatic seed is deleted. A new car starts empty.

**Decision (Kevin), and it is the largest change on this ticket.** Nothing is written to a car's
schedule until a populate is run and its diff accepted. A new car reads:

> `No schedule yet - populate from the factory recommendation?`

**One write path instead of two.** `onboardPendingVehicles` (`VehicleController.kt:653-659`), which
fires on every service start, goes - and with it the mechanism that put **54 rows and 49 empty
anchors** across Kevin's roster (ticket 01) without him ever asking for one. `applyServiceIntervals`'
automatic callers in `registerDirect` / `addVehicle` / `correctVehicle` go too; registering a car
stops being a thing that silently produces a schedule.

**`Vehicle.onboarded` becomes vestigial.** It exists to mean "the one-time lookup has run"
(`Vehicle.kt:28-30`) and there is no longer a one-time lookup. Leave the column (removing it is a
destructive migration for no gain) but **stop writing it, and say in its doc that it is dead** -
otherwise it is the next `refreshServiceIntervals`, a field that looks live and is not.

### The diff has three categories, not two

**Decision (Kevin).**

| Category | What it means |
|---|---|
| **Would add** | in the factory schedule, not on file |
| **Would change** | on file with a different interval - shows current beside proposed, **and who authored the current value** (ticket 06's flag) |
| **Not in the factory schedule** | on file, factory does not list it - with a delete offered per row |

That third category is the one Kevin chose and it is the one that cleans up. On his Jeep it will
catch `Brake Fluid Flush`, which ticket 02 proved **is not in the XJ's factory schedule at all**
(brake fluid appears only as a monthly level check), and it would catch a `Cabin Air Filter` if one
were ever seeded - **the XJ does not have one.**

**It must distinguish LEGION's inventions from Kevin's own additions.** A hand-added
`Transmission Flush` and an invented `Brake Fluid Flush` look identical in this category unless
`intervalSource` says which is `SEEDED` and which is `CONFIRMED`. **That is why this ticket needed
ticket 06**, and the delete offer must be worded differently for the two - proposing to delete
something Kevin added himself is a different act from proposing to delete something LEGION invented.

**Nothing writes until accepted.** Per-row, not all-or-nothing.

### Name matching, resolved by ticket 07

Ticket 02 counted **26 distinct factory service names** against `SERVICE_KEYWORDS`' ten. Ticket 07
settled the mechanism: **the canonicaliser is a comparator, never a rewriter.** So the diff
canonicalises both sides and compares case-insensitively to decide whether "Drain and refill front
and rear axles" is the existing "Differential Fluid Service", and **a near-miss is shown as a
question**, not resolved silently in either direction.

**The titlecase bug is a hard prerequisite** (`VehicleController.kt:201` titlecases only the first
character). Without that fix this feature multiplies duplicates rather than reducing them - which is
this ticket's own stated risk, and it now has a named cause rather than a vague worry.

### `engine` column, and the migration

`Vehicle` has no engine field and Kevin wants it in manual input (a 4.0L XJ and a 2.5L differ on
plugs and capacities - ticket 02). Additive, `TEXT NOT NULL DEFAULT ''`.

**Tickets 06 (`intervalSource`) and 07 (`deleted`) already share a bump to v20.** This is a
different table, so it can ride the same migration or take v21 - **do not hold 06/07 for it.**
CLAUDE.md §5 either way: verbatim generated SQL, additive, `exportSchema`, schema JSON committed,
migration test, no destructive fallback. Confirm the `createSql` in `app/schemas/` afterwards.

### Mileage at registration reuses ticket 10's control

Kevin asked for mileage in the manual-input set, and it closes the exact hole that broke his Jeep -
a fresh car computing due dates against odometer zero. **It reuses ticket 10's odometer control
rather than duplicating it**; two implementations would be two places to get the estimate label
wrong.

### The VIN path is already built

Question 7 is **resolved by shipped code** (commit `b499169`, verified on the device 2026-08-15).
`VinDecoder.decodeAll` returns identity and specs from one call; `refreshFromVin` applies the
identity under fill-blanks-never-overwrite; `reconcileIdentityFromStoredVin` repairs from a stored
VIN without the adapter. This ticket **consumes** that rather than rebuilding it.

Correcting the ticket's own premise: `vehicle_specs` does not carry year/make/model, but
**`VinDecoder.decode` does** - it parses them from the same vPIC response `decodeSpecs` reads.

### Where the trigger lives

Two entry points, one implementation: the **specs screen** (beside SYNC ID FROM VIN, where a VIN
already lives) and the **full schedule screen** from ticket 09 (where an empty schedule is visible).
Both call the same function.

### Verification

1. On Kevin's real Jeep: run a populate, confirm the diff shows **oil moving 3,000 -> 7,500** and
   lists `Brake Fluid Flush` under "not in the factory schedule".
2. **Confirm nothing is written until accepted** - pull the database mid-diff.
3. Accept, force-stop, reopen, pull again. **The service anchors at 118,483 must survive untouched** -
   losing them is the failure this map already suffered once, and it is the check that matters most.
4. Re-run the populate immediately and confirm it produces **no duplicates** - the risk this ticket
   was charted against.
5. Register a fresh car and confirm it starts **empty**, with the populate prompt.

### Assumptions ledger

- `traced`: `onboardPendingVehicles`' service-start trigger and its callers; `Vehicle.onboarded`'s
  stated purpose; that `VinDecoder.decode` parses year/make/model from the same response;
  the titlecase fallback.
- `on-device`: the 54 rows / 49 empty anchors; the identity write-back working on the real Jeep.
- `sourced` (ticket 02): brake fluid absent from the XJ schedule; no cabin air filter; 26 names.
- `reasoned`: that deleting the auto-seed removes the duplicate-generation path entirely. It removes
  the *automatic* one; the populate can still generate duplicates if the matching is weak, which is
  why (4) above is a gate.
- **Not built.**

