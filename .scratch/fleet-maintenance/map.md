# Map: Fleet maintenance tracking

Label: wayfinder:map
Charted: 2026-08-15 (Kevin + Opus)

## Destination

The fleet aspect's maintenance layer rebuilt and **shipped on the phone**: a schedule Kevin owns
rather than one an LLM guessed at, editable by hand (add / edit / delete), anchored to an odometer
that tracks between manual verifications, with a service-history screen, fleet spend, and a recall
check reachable without speaking. The map closes when Kevin's real 1998 Jeep Cherokee row on the
real device shows a 7,500-mile oil interval he set himself, a tire rotation he added himself, and
his two existing service records on a screen.

## Notes

- **Execution is IN SCOPE for this map**, overriding wayfinder's plan-don't-do default. Same shape
  as `mission-control`: decision tickets first, build tickets graduate from fog.
- **The visual language is settled and not reopened.** `mission-control` closed 2026-08-14 and
  everything it decided - palette, bezel, tiling grammar, `DeckRow`/`DeckPane`/`DeckMeter`/
  `DeckTag`, the alarm tiers, the bundled mono - is law here. This map changes what fleet *knows*
  and what it *lets you do*, not what it looks like. New surfaces adopt the existing vocabulary.
- **Charting decisions, binding on every ticket** (grilled 2026-08-15, Kevin):
  1. **Destination is shipped and QA'd on the device**, not decided.
  2. **The 1998 Jeep Cherokee is Kevin's real car.** This collides head-on with `Vehicle.kt:42-48`,
     where the no-OBD placeholder seed *is also* a 1998 Jeep Cherokee. Every ticket that reasons
     about which car is active must treat "looks like the placeholder" as ambiguous evidence.
     Ticket 01 exists to end the ambiguity with data.
  3. **Manual odometer always wins and resets the baseline.** Drift is discarded, not reconciled.
  4. **Logging a service resets that item's clock by matching the service name.** Kevin took this
     over the pick-from-a-list alternative with the silent-miss risk stated. Ticket 08 exists to
     make name-matching safe rather than to re-litigate the choice.
  5. **A seeded interval is a guess and must say so until Kevin confirms it.** This is CLAUDE.md §4
     rule 5 applied to the maintenance schedule: an LLM-supplied interval is not a fact the car
     stated, so it is labelled an estimate, in words, on every surface that renders it.
  6. **Due = whichever comes first, miles or months.** Both intervals per item.
  7. **Fleet spend is fleet-local.** Cost of ownership, cost per mile, spend by service type, shown
     on fleet surfaces. **No ledger reconciliation** - that is out of scope (see below).
  8. **The OBD adapter is in the port on nearly every drive**, so speed-integrated distance is worth
     having. It is still an estimate and ticket 10 decides what the screen says about it.
- **This map's core defect class is the silent no-op.** Kevin asked the assistant to change an
  interval; it reported success and changed nothing, because the only write path that could have
  done it (`VehicleController.refreshServiceIntervals`) has **zero callers**, and the seed path uses
  `@Insert(IGNORE)` so it cannot overwrite an existing row. That is the same sin as CLAUDE.md §4
  rule 6's silently-dropped line. **Any ticket that adds a write must make "reported success,
  wrote nothing" structurally impossible**, not merely unlikely.
- **Count before you resolve.** Carried from `mission-control`: if a ticket's question names a
  category of thing, grep for it and count it first. It already paid here at charting - "the car
  label" turned out to be **four different resolution rules across twelve surfaces**.
- **The phone holds Kevin's REAL data. `install -r` only; never `adb uninstall`, never `pm clear`.**
  Carried verbatim from `mission-control`. Auto Backup does not restore Keystore material, the
  Gemini key, Drive authorisation, or runtime permissions.
- **`adb exec-out` for binary pulls, never `adb shell cat`. Verify every install by SHA-256**,
  never by "Success". `adb` is not on PATH - see the wireless-adb memory note.
- Skills sessions should consult: `domain-modeling` (the schedule/record/anchor vocabulary is
  genuinely muddled), `grilling`, `prototype`, and the vendored Compose skills for any build ticket.

## Decisions so far

<!-- one line per closed ticket: gist + link -->

- [What the real data on the phone actually says](issues/01-what-the-real-data-says.md) - **the
  Jeep has no identity and no odometer.** `make`/`model`/`year` are empty and `odometerBaseline` is
  0, so `displayLabel` returns blank (that is the `THIS CAR` bug - **not** resolution order, and
  **not** the wrong active car, which is an explicit pick and correct) and **`currentMileage`
  evaluates to 0 against service anchors at 118,483**. The maintenance drilldown therefore renders
  **three rows out of ten** - `buildDueRows` silently drops all seven unanchored items - and reports
  **the oil due in 121,450 miles**. The identity is not lost, only unwritten: `vehicle_specs` holds
  a fully decoded VIN from 2026-07-26 that never wrote back to `vehicles`, **so `check_recalls`
  passes its `confirmed` gate and queries NHTSA with empty parameters.** Ticket 08's orphan rows
  exist in the wild (`Brake Fluid` + `Brake Pads`, anchor-only, beside the seeded `Brake Fluid
  Flush`), `cost` is null on both service records, `neverDone` has **never been used once** in 54
  rows, and the odometer estimator has **never accumulated a single mile** on this car despite 938
  speed samples. **The 3,000 is confirmed - exactly one row app-wide has it.** How the row lost its
  identity is unexplained; migrations 16-19, `correctVehicle` and `registerDirect` are all ruled out
  by reading. Charted as ticket 13.

## Not yet specified

- **The build tickets.** Shape is known - schema bump, DAO surface, edit affordances, the rebuilt
  drilldown, the service-history screen, the odometer entry, the recall button - but each one's
  contents depend on the decision ticket above it. They graduate as decisions land, the way
  `mission-control` tickets 13-16 did.
- **Room migration plan.** At least one bump is coming (a per-item provenance flag from ticket 06,
  probably a cost writer from ticket 11, possibly a tombstone from ticket 07). Whether that is one
  bump or three is not decidable until those three tickets resolve. Room is at **v19**.
- **What the fleet advisor does with a schedule Kevin owns.** `AdvisorProposalExecutor`'s
  `set_maintenance_item` is currently the *only* live interval writer. Once a UI edit path exists,
  the advisor's role changes from sole author to proposer, and its allowlist may need revisiting.
- **Whether the seeded schedule should be re-seedable at all.** Touched by tickets 05 and 06, but
  the question of what happens when Kevin changes the car's identity after curating a schedule by
  hand is not sharp enough to ticket yet.
- **Voice parity.** Every new UI affordance has a voice twin that may or may not need updating
  (`log_service`, `set_odometer`, `log_past_service`, `accept_proposal`). Deferred until the UI
  shape is settled, so the tools follow the screens rather than the reverse.

## Out of scope

- **Reconciling maintenance costs against the ledger.** Kevin ruled fleet spend stays fleet-local.
  Dragging CLAUDE.md §4's gate into fleet roughly doubles the map and buys a matching problem
  nobody has asked for.
- **Re-deciding the mission-control visual language.** Settled 2026-08-14, closed, not reopened.
- **The oil-analysis surface.** `OilAnalysisDrilldown` already exists and `oil_analyses` has no
  writer, which is a real gap - but it is a lab-result ingestion problem, not a maintenance-schedule
  problem. Separate effort.
- **Build sheet / `BuildEntry`.** The drilldown's "N build sheet entries on file, no screen yet"
  line is the same shape as the service-record gap but it is modifications, not maintenance.
- **The Android Auto fleet surface.** `.scratch/android-auto/` owns that and has its own blockers.
