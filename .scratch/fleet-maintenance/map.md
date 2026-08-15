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

- [One car label rule](issues/04-one-car-label-rule.md) - **counting reframed it a fourth time**:
  charted as twelve surfaces, actually **24 `displayLabel` call sites, 4 `carLabel`, ~16 raw `name`
  reads and FIVE different last-resort strings**, with two surfaces the chart missed entirely.
  Resolved to **one rule everywhere, screen and speech alike**: `nickname (year make model)`,
  falling back cleanly when either half is missing, **two lines where it does not fit** (the
  `carLabel`/`carSpecPrefix` shape `CarRows` already has), and `"a car you haven't named yet"` as
  the single last resort. Two refinements added on resolution rather than by Kevin: **trim is
  excluded from the spec**, and a **de-duplication clause** for when the spec already contains the
  nickname - his own row (`name` = `1998 Jeep Cherokee`, spec = `... Limited`) is the case that
  forces both. **`"this car"` is deleted, not filtered**: the seed stops writing it and the two
  archived rows carrying it get cleaned, because a magic value that must be filtered on read is how
  twelve surfaces became twenty-four. Also rules that **chrome may be uppercased and driver-typed
  data may not** (the driving-mode HUD is why the placeholder shouted). **Unblocks ticket 12.**
  Records a stale-parent bug found on-device: after the identity write landed, FLEET still rendered
  `THIS CAR` until the tab was switched - a write nobody could see, one layer up from the map's
  core defect, and it names the check that would have missed it.
- **[BUILT, shipped and verified on the phone: the VIN identity write-back]** (part of ticket 04,
  commit `b499169`). `refreshFromVin` decoded the VIN, wrote sixteen spec fields and **discarded the
  year/make/model/trim parsed from the same response**. Now `decodeAll` makes one call and the
  identity is applied under a **fill-blanks, never-overwrite** policy, where **a conflict on any one
  field aborts the whole write** - because a disagreement is evidence the decode may not describe
  this car at all. Deliberately does **not** reuse `setIdentity`, which stamps `confirmed = 1`: a
  lookup filling in blanks must not claim the driver's consent. **On Kevin's real Jeep: `make ''` ->
  `Jeep`, `model ''` -> `Cherokee`, `year 0` -> `1998`, `trim ''` -> `Limited`, everything else
  byte-identical, other four rows untouched.** Review caught two things first: `READ VIN` was doing
  the write-back and **discarding the outcome**, and untrimmed comparison made false conflicts.

- [The Jeep row lost its identity and its odometer](issues/13-the-jeep-row-lost-its-identity.md) -
  **the mechanism is certain, the trigger is not, and both statements are load-bearing.** Proof came
  off the disk, not off a timeline: `applyServiceIntervals` sets `onboarded = true` in the same call
  that writes the schedule, so **ten seeded items alongside `onboarded = 0` cannot coexist** on a row
  that was never overwritten. Cause: `VehicleDao.upsert` is `@Insert(REPLACE)` and `seedVehicle`
  **persists an all-defaults row on any `getByMac` miss** - one miss is permanent, total and silent.
  `TelemetryRecorder` calls that path **every 30 seconds while driving**, and `withDatabaseLock`'s own
  doc named that exact thread as a hazard on 2026-08-13, the day of the damage; **the guard landed
  2026-08-15, two days late.** Six suspects eliminated by evidence, including `DatabaseSnapshot.restore`
  - **that file did not exist yet.** Kevin took the deepest fix: seeding never persists, whole-row
  REPLACE stops being how a vehicle is edited, `registerDirect`'s silent field loss goes with it, and
  trip miles accumulate in SQL. Odometer restored as **~118,374 derived from the service record,
  stamped with that record's own date so it carries its staleness** - an estimate, never a reading.
  **No schema change.** Also records a confound: the old placeholder was itself a 1998 Jeep Cherokee,
  so **no row written before 2026-08-15 can be told from a placeholder by make/model/year.**
- [Can the adapter read a real odometer?](issues/03-reading-a-real-odometer-over-obd.md) - **No.
  Two independent kills**: the XJ's odometer is on Chrysler's **CCD bus** and the ELM327 has no CCD
  transceiver, and the Cherokee **kept CCD to end of production** while the rest of Chrysler moved to
  the J1850 the ELM327 does speak. PID `$A6` postdates it by 21 years. `$21` is permanently 0 on a
  healthy car **by definition**; `$31` is optional in 1998, resets on battery disconnect, and
  saturates at ~40,700 mi without wrapping. **The dash odometer is itself a speed integration off the
  same VSS** - so LEGION computes the same measurement, far more sparsely, and should therefore
  **prefer OBD speed over GPS** (it currently prefers GPS). Accuracy is a **~10% one-directional
  undercount**, from five named losses that only ever lose miles. Two live defects surfaced: the
  0.001-5.0 mi window lets **idling accrue phantom miles**, and `wanted("010D")` **latches off
  permanently** after 3 failures.
- [What a 1998 Jeep Cherokee actually needs](issues/02-what-a-1998-xj-actually-needs.md) - **Kevin's
  7,500 IS the factory number.** Schedule A (normal) is 7,500 mi or 6 months; Schedule B (severe) is
  3,000 mi with **no time interval at all**. The 3,000 on his phone is the correct answer to a
  question nobody meant to ask - `lookupServiceIntervals` hardcodes SEVERE into its prompt. **The LLM
  was not wrong; the prompt was**, and fixing it yields 7,500 with no further intervention. Four
  findings bite other tickets: Schedule B publishing no time intervals means **the data model must
  tolerate a mileage-only item**; **brake fluid is not in the factory schedule at all**, so the
  seeded `Brake Fluid Flush` was invented rather than retrieved; **the XJ has no cabin air filter**;
  and **it never used HOAT** - the trap runs opposite to the folklore this map's own ticket repeated.
  **26 distinct factory strings against `SERVICE_KEYWORDS`' ten** - the canonical list fails the test
  this ticket existed to run.

- [What the real data on the phone actually says](issues/01-what-the-real-data-says.md) - **the
  Jeep has no identity and no odometer.** `make`/`model`/`year` are empty and `odometerBaseline` is
  0, so `displayLabel` returns blank (that is the `THIS CAR` bug - **not** resolution order, and
  **not** the wrong active car, which is an explicit pick and correct) and **`currentMileage`
  evaluates to 0 against service anchors at 118,483**. The maintenance drilldown therefore renders
  **three rows out of ten** - `buildDueRows` silently drops all seven unanchored items - and reports
  **the oil due in 121,450 miles**. The identity is not lost, only unwritten: `vehicle_specs` holds
  a fully decoded VIN from 2026-07-26 that never wrote back to `vehicles`, **so `check_recalls`
  passes its `confirmed` gate and then returns an empty list that reads exactly like "no open
  recalls"** (corrected 2026-08-15: `fetchRecalls` guards on blank year/make/model and makes no
  request at all - the earlier claim that it queried NHTSA with empty parameters was wrong in
  mechanism, though not in consequence). Ticket 08's orphan rows
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
- **Room migration plan.** At least one bump is coming, and ticket 14 has now named a concrete one:
  **an `engine` column on `vehicles`** (Kevin asked for it in the manual-input set, and ticket 02
  showed why - a 4.0L XJ and a 2.5L differ on plugs and capacities). Plus a per-item provenance flag
  from ticket 06, probably a cost writer from ticket 11, possibly a tombstone from ticket 07.
  Whether that is one bump or four is not decidable until those tickets resolve. Room is at **v19**
  and ticket 13's fix did **not** move it.
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
