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

- [Service history, cost and fleet spend](issues/11-service-history-cost-and-fleet-spend.md) -
  **`cost` migrates `Double` -> `Long` cents** (§4 rule 3, no exception), and the migration is free
  **because the column is provably empty** - null on both records, no writer anywhere. Every later
  moment costs more. `BuildEntry.cost` becomes the deliberate odd one out and gets a note saying so.
  All four spend figures, **two carrying caveats that are the whole point**: the total must state
  **how many records it covers** (both of Kevin's are cost-less, so the honest answer today is "no
  costs logged", not "$0"), and **cost per mile must REFUSE while the odometer is 0** rather than
  divide by an estimate. Spend-by-type groups on the **canonicalised** name or the duplicate rows
  split one category in two. Edit and delete both, **but a service-record delete is LOCAL ONLY** -
  `service_records` syncs UNION, and the sync doc is explicit that tombstones cannot work under it.
  Acceptable only because it is stated rather than discovered.
- [Populate from the factory schedule](issues/14-populate-from-the-factory-schedule.md) - **the
  automatic seed is DELETED; a new car starts empty** and says "no schedule yet - populate it?".
  That removes the mechanism that put 54 rows and 49 empty anchors on the roster without Kevin
  asking, and makes `Vehicle.onboarded` vestigial (left in place, stopped being written, documented
  as dead so it is not the next `refreshServiceIntervals`). The diff has **three categories**:
  would-add, would-change (with **who authored the current value**), and **"not in the factory
  schedule"** with a delete offered - which is what finally catches the invented `Brake Fluid Flush`.
  It must word an invented row differently from one Kevin added himself. The **titlecase fix is a
  hard prerequisite**, or this feature multiplies duplicates instead of reducing them. `engine`
  column additive; the VIN path is **already built and verified on the device**.
- [A recall button](issues/12-a-recall-button.md) - **identity-present gates both paths**;
  `confirmed` stops being the recall gate. Its original justification **expired in `a09aa68`**: it
  existed to block a placeholder that was itself a 1998 Jeep Cherokee, and seeding is now blank for
  every id, so a blank row cannot pass an identity test anyway. Checked rather than assumed. The
  voice tool and the proactive push now agree on the same car. **"No recalls found" must read as a
  completed check, never as an empty state** - it is the expected answer on a 28-year-old vehicle
  and the one most easily mistaken for a broken button, so it and the failure state get screenshotted
  side by side. `recallAlertsEnabled` gets a Settings toggle or gets deleted.
- [Odometer truth and drift](issues/10-odometer-truth-and-drift.md) - **always labelled between
  readings**, no threshold and no window where a drifting number renders bare, because ticket 03
  measured the estimate at **~5-15% low, always one-directional**. The confirmed reading renders
  bare; only the estimate carries the caveat, spoken as well as rendered. **The estimator now
  prefers OBD speed over GPS, reversing shipped behaviour** - the dash odometer is itself a PCM
  speed integration off the same VSS, so this puts the estimate and the reading that resets it in
  one reference frame. Two live defects fixed: the **0.001-mile floor is 1.61 m**, below GPS jitter,
  so idling accrued phantom miles; and **`wanted("010D")` latches off permanently** after 3
  failures, a silent zero in the odometer's own supply line. One entry control, reused by tickets 09
  and 14. Drift is **logged, not shown**.
- [The maintenance surface, rebuilt](issues/09-the-maintenance-surface-rebuilt.md) - **three
  surfaces**: triage, full schedule, item detail. Unknown-anchor items are **counted on triage, not
  listed** (`7 items with no history - see full schedule`) - they stop being invisible without
  pretending to be urgent, and that line is the natural backfill prompt. **Every action lives on the
  item detail screen**, which is what makes the density work: a tappable row costs 48dp against a
  560dp budget, so inline edit/log/add/delete on every row would have blown it. **The tile must stop
  saying `OK`** while seven items are unknown - it currently reads `OK / NEXT BRAKE FLUID -` on
  Kevin's phone, off an orphan row with no interval. `dueFraction`'s month-is-30-days approximation
  **stops being cosmetic** once months drive due-ness. **Unblocks 11.**
- [Matching a logged service to an item](issues/08-matching-a-logged-service-to-an-item.md) -
  **record it, add the missing item, and SAY SO.** The behaviour is unchanged; **the silence is what
  goes** - Kevin's `Brake Fluid` and `Brake Pads` orphans were created by this exact path and he
  learned of them from a database pull three days later. Matching is **deterministic**, using ticket
  07's comparator, never an LLM - which would make two identical utterances match differently.
  New finding: **a backfill silently overwrote a precise record's anchor** (118,374 -> 118,483,
  fourteen seconds apart, date nulled), so **a backfill may not overwrite an anchor a `ServiceRecord`
  supports without saying so.** The titlecase fix is a prerequisite here too.

- [Hand-added items, and what delete means](issues/07-hand-added-items-and-what-delete-means.md) -
  **the ticket's premise was wrong and the codebase already had the answer.** It was charted
  believing a tombstone would have to be invented; `car_tasks` and `places` have carried one since
  B19, the sync snapshot deliberately does not filter it, and it works **precisely because those
  tables are LWW** - which `maintenance_items` also is. So delete is a **real delete via the
  existing tombstone**, reusing `TOMBSTONE_HORIZON_MS` rather than inventing a second constant.
  **Service records survive a deleted item**: a `ServiceRecord` is a fact about work actually done,
  and deleting a schedule row does not un-do it. **A hand-typed name is stored verbatim**, and the
  canonicaliser is demoted to a **comparator** that only raises "this looks like Oil Change - add
  anyway?" - it must never rewrite a name Kevin chose, because
  `canonicalizeServiceName("Oil filter change")` returns `"Oil Change"` and would silently merge two
  items. **Three-way anchor picker** (never done / don't know / done at ...), which finally makes
  `neverDone` reachable - it is `true` on **0 of 54 rows** because no control has ever set it.
  Found the duplicate engine itself: the canonicaliser's fallback **titlecases only the first
  character**, so hand-typed `"transfer case fluid"` and seeded `"Transfer Case Fluid"` are
  different primary keys. **Rename ruled out of scope.** `deleted` is additive and **should ship as
  ONE migration with ticket 06's `intervalSource`, v19 -> v20.** **Unblocks 08 and 14.**

- [A seeded interval is a guess](issues/06-a-seeded-interval-is-a-guess.md) - **a `[GUESS]` DeckTag
  on the row**, which satisfies §4 rule 7 *because the tag carries the word* - so it may never
  degrade to a coloured dot or an icon under layout pressure. Counting found **six surfaces render
  or speak an interval, and two of them are LLM prompts** (`MaintenanceAgent`, `FleetPlaybook`):
  feeding an unlabelled guess into a model that states it back confidently is how an estimate
  launders itself into a fact, so **a disclosure that stops at the screen just moves the lie to the
  loudest channel.** One new TEXT column, `intervalSource` = `SEEDED`|`CONFIRMED`, **v19 -> v20**,
  every existing row defaulting to `SEEDED` (correct - all 54 are LLM-produced, and `updatedAt`
  cannot reveal authorship because the Kotlin default stamps construction). Deliberately **not**
  reusing `IngestMethod`: that vocabulary describes what survived the §4 gate, and an interval never
  enters it. Confirm-all exists but **re-states every value first**; editing or accepting confirms,
  with no separate step. **No bundled factory table** - the LLM lookup stays for every car, so the
  3,000 -> 7,500 correction rests entirely on the shipped prompt fix plus Kevin confirming it, and
  nothing deterministic backs it up. Two refinements added on resolution: **a null interval gets no
  tag** (Kevin's `Brake Fluid`/`Brake Pads` orphans have no number to doubt), and the tag's claim is
  **broader than the number** - ticket 02 proved a seeded row can invent an item that does not exist
  on the car at all (`Brake Fluid Flush`; the XJ has no cabin air filter).
  **Unblocks the builds of 05, 09 and 14.**

- [An interval edit that actually sticks](issues/05-an-edit-that-actually-sticks.md) - **the
  no-op guard becomes law for this map**: every write returns its affected row count and a zero is
  an error surfaced in words, never a shrug. Precedent set twice already today
  (`setOdometerBaseline`, `applyDecodedIdentity`); from here a write path that discards its row
  count is a review failure. Its conversational twin: **voice reads the value back**, because a
  read-back cannot be produced if the write did not land - which is exactly how Kevin's original
  "change it to 7,500" should have failed, out loud. **A driver-owned interval may only be changed
  by something that names the change and takes a confirmation**: ticket 14's populate diff, or
  `accept_proposal`, which demotes the advisor from sole author to proposer. The LLM seed may never
  overwrite one - today it only fails to because `insertAll` happens to be `IGNORE`, protection
  nobody chose. **`maintenance_items` gets targeted writes too** (`setIntervals`/`setAnchor`/
  `setNeverDone`/`deleteItem`), closing the same whole-row REPLACE class `VehicleDao` just shed -
  `logServiceDirect` does read-modify-write through it today. **`refreshServiceIntervals` is
  deleted**; ticket 14 rebuilds the capability with a diff. `LiveToolbox`'s 23 hardcoded
  `success = true` are fixed **only on the write paths that can now fail** - the 194-call-site sweep
  is a scoped-out boundary, not an oversight, and is charted as fog.
  **Unblocks 07. But 06 must resolve before 05 is BUILT** - decision 1 is unenforceable without its
  provenance flag.

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

- [An empty factory list is a failed lookup](issues/17-an-empty-factory-list-is-a-failed-lookup.md) -
  **an empty factory schedule is refused exactly as a `null` one is**, in `buildPopulateDiff` itself
  rather than at its caller, so a test can pin it. The previous guard covered only `null`, while the
  lookup prompt instructs the model to signal not-found as `[]` - so the first real populate on
  Kevin's Jeep proposed deleting all eight of his items, oil change included. Built, and the guard
  was then seen catching a real empty lookup on-device. It also surfaced **ticket 18**, which is open
  and which limits how far ticket 14's populate can be trusted.

- [The factory lookup is not stable enough to diff against](issues/18-the-factory-lookup-is-not-stable-enough-to-diff-against.md) -
  **the lookup is NOT fixed, deliberately** (no sampling, no citation requirement - Kevin's call);
  manual entry stays the primary path. What changed is that a populate accept no longer writes
  `CONFIRMED`. `LOOKUP` is a third provenance value, `isGuessTag` tests `!= "CONFIRMED"` so a new
  value defaults to disclosed rather than silent, and every "the factory schedule doesn't list it"
  is now "this lookup didn't mention it". Four runs on one car in five minutes disagreed on three of
  eight items; the app now says only what it actually knows.

## Not yet specified

- **The build tickets.** **Every decision on this map is now made** - what remains is execution, and
  it graduates into build tickets the way `mission-control` tickets 13-16 did. Rough order, dictated
  by the schema and by what gates what:
  1. **The migration** (below) - gates almost everything else.
  2. **DAO + write paths**: targeted writes on `maintenance_items`, the no-op guard, delete
     `refreshServiceIntervals`, the titlecase fix, the `success` flags on write tools. (05, 07, 08)
  3. **The label rule** across 24+ call sites, and `"this car"` deleted. (04)
  4. **Odometer**: OBD-first, the acceptance floor, the `wanted()` latch, the entry control and its
     labelling. (10)
  5. **Screens**: triage / full schedule / item detail, then history and spend. (09, 11)
  6. **Populate** with its three-category diff, and the auto-seed removed. (14)
  7. **Recall button**, small and mostly reachability. (12)
- **THE MIGRATION, v19 -> v20**, now fully specified by the resolved tickets and best done **once**:
  | Table | Column | From |
  |---|---|---|
  | `maintenance_items` | `intervalSource TEXT NOT NULL DEFAULT 'SEEDED'` | ticket 06 |
  | `maintenance_items` | `deleted INTEGER NOT NULL DEFAULT 0` | ticket 07 |
  | `vehicles` | `engine TEXT NOT NULL DEFAULT ''` | ticket 14 |
  | `service_records` | `cost` REAL -> `costCents` INTEGER | ticket 11 |
  The first three are additive. **The fourth is not**, and is the map's one stated exception to §5's
  additive-only rule, justified by the column being provably empty - **verify that with
  `SELECT COUNT(*) FROM service_records WHERE cost IS NOT NULL` against a copy of Kevin's real
  database before writing it.** Everything else §5 requires still applies: verbatim generated SQL,
  `exportSchema`, committed schema JSON, migration test, no destructive fallback.
- **Empty / offline / loading copy per surface.** The rules are law (worded, never colour or glyph
  alone; estimates labelled; "no recalls" must read as a completed check) but the exact wording is
  written inside each build ticket.
- **The ship pass.** A final on-device sweep once the builds land, including the checks each ticket
  named as owed. **Nothing on this map has been seen on a screen yet** beyond the two fixes that
  shipped 2026-08-15 (the vehicle-row overwrite, the VIN write-back). Compose previews have never
  rendered on this project, any screen, ever - installing and looking is the substitute and it has
  caught every real bug so far.
- **[Ticket 15](issues/15-isdue-and-the-digest-inherit-the-same-two-gaps.md), charted 2026-08-15
  from ticket 09's review.** Two pre-existing gaps this map closed elsewhere but not here:
  `VehicleController.isDue` has **no odometer-unset guard**, so an item can sort into OVERDUE off an
  odometer nobody confirmed - the render path would then say `OVERDUE` and `odometer not set` on the
  same row. And `FleetDigestBuilder` **was never one of ticket 06's six audited surfaces**, so its
  maintenance lines carry no `[GUESS]` while feeding `AdvisorBriefs`. Both deliberately left out of
  ticket 09's diff: `isDue`'s contract has callers across the digests, `nextService` and the advisor.
  The audit lesson is on the ticket - ticket 06 grepped the raw interval fields, and this consumer
  reads an already-formatted string, so it never matched.
- **What the fleet advisor does with a schedule Kevin owns.** `AdvisorProposalExecutor`'s
  `set_maintenance_item` is currently the *only* live interval writer. Once a UI edit path exists,
  the advisor's role changes from sole author to proposer, and its allowlist may need revisiting.
- **Whether the seeded schedule should be re-seedable at all.** Touched by tickets 05 and 06, but
  the question of what happens when Kevin changes the car's identity after curating a schedule by
  hand is not sharp enough to ticket yet.
- **Voice parity.** Every new UI affordance has a voice twin that may or may not need updating
  (`log_service`, `set_odometer`, `log_past_service`, `accept_proposal`). Deferred until the UI
  shape is settled, so the tools follow the screens rather than the reverse. Ticket 05 has already
  claimed one of these: a new `set_maintenance_interval` Live tool, with a read-back.
- **`LiveToolbox`'s success/message contract.** 23 of its 194 `result(` calls hardcode
  `success = true`, so the JSON envelope can assert success over a message that is a refusal.
  Ticket 05 fixes only the write paths that can now fail and **explicitly scopes out** the rest.
  The full sweep is its own effort, and it needs its own count before anyone touches it - the
  hardcoded ones are a minority, so a blind sweep would do more harm than good.

## Carried out of this effort, unbuilt

**The effort is closed (Kevin, 2026-08-15). One ticket is left open on purpose, not lost.**

- [Ticket 06 audited a dead surface and missed the live one](issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md) -
  **open and unbuilt.** Re-verified at close rather than taken from an earlier note:
  `MaintenanceAgent.describeItem` (`vehicle/MaintenanceAgent.kt:74`) still reads `intervalMiles`/
  `intervalMonths` raw with no `intervalSource` check, and it is the live formatter that builds the
  pre-seeded prompt the model actually reads. `CarToolbelt.maintenanceSchedule`
  (`vehicle/CarToolbelt.kt:142`) still exists with no caller. So the model-facing half of ticket 06's
  disclosure was never built, and this is the same §4 rule 5 harm tickets 17 and 18 spent the day
  closing on the UI side - an unlabelled guess fed to a model that states it back confidently.
  Small, self-contained, and it does not block anything else on this map.

  It stays a ticket rather than moving to **Out of scope**, because it is squarely inside this map's
  destination - it is simply unbuilt at the moment the effort closed.

## Out of scope

- **Renaming a maintenance item.** Ruled out on ticket 07, 2026-08-15. The PK is
  `(vehicleId, serviceName)`, so a rename is tombstone-plus-insert with the anchors carried by hand,
  plus a migration path for `service_records` that reference the old name as a string. Kevin has not
  asked for it and delete-and-re-add expresses it.

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
