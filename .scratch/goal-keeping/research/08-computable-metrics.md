# What else in the app's own records can be computed deterministically

Answers `.scratch/goal-keeping/issues/08-what-else-is-computable.md`. Repo investigation,
2026-08-18, branch `feat/mission-control`. Every file:line below was opened, not remembered.

## Summary

- **3 keys are documented; exactly 2 are implemented, and one of those 2 is unit-unsafe.**
  `savings_balance_cents` resolves in two places; `bodyweight_kg` resolves in one and does not
  convert to kg; `odometer_miles` resolves NOWHERE despite being named in `Goal`'s doc comment and
  in `set_goal`'s own tool schema.
- **`metric_key` is unvalidated free text from the model.** `LiveToolbox.kt:3211` takes whatever
  string arrives; the schema at `LiveToolbox.kt:1316-1321` is `"string"` with no `enum`, unlike
  `aspect` two params above it which DOES carry `enum = GoalController.ASPECTS`
  (`LiveToolbox.kt:1311`). Widening the list is a code change; INVENTING one is a voice turn.
- **~9 further metrics are genuinely computable today**, most with a DAO method already written and
  most already computed inside a digest builder.
- **The flag list at the bottom is the part that matters.** Six metrics look computable and are not,
  and two of the three existing keys are among them.

## Candidate table

Direction per ticket call 3. `ACCUM` may call `GoalProgress.accumulationProgress`
(`goals/GoalProgress.kt:31-34`); `REDUC` and `MAINT` must never (its own doc, lines 19-22, and
`ui/goals/GoalsPanel.kt:170-176`).

| metricKey | Source table | Current value | Trend over window | Dir | Trust tier | Digest already computes it |
|---|---|---|---|---|---|---|
| `bodyweight_kg` *(exists)* | `bodyweight_logs` | `BodyweightLogDao.mostRecent()` `data/local/BodyweightLogDao.kt:26-27` | `BodyweightLogDao.forWindow()` `:23-24`, 4 weekly averages | REDUC (usually) | `REPORTED` always, stored on the row `data/local/BodyweightLog.kt:28`, doc `:15-16` | **Yes.** `BioDigestBuilder.weightLine` `advisor/digest/BioDigestBuilder.kt:71-103` (4 wk avgs + trend); `HomeDigestBuilder.bioHeadline` `:99-114`; screen `ui/goals/GoalsPanel.kt:205-207` |
| `savings_balance_cents` *(exists)* | `ledger_transactions` | `GoalProgress.savingsBalanceCents` `goals/GoalProgress.kt:46-61` over `LedgerTransactionDao.latestBalanceCents` `data/local/LedgerTransactionDao.kt:43-47` | **would need writing** - no balance-at-date query exists | ACCUM | `PROVEN`/`REPORTED` via `hasReconciledRows` `data/local/LedgerTransactionDao.kt:234-238` + `combinedTier` `plan/Plan.kt:46` | **Yes.** `CredDigestBuilder.goalLines` `advisor/digest/CredDigestBuilder.kt:205-230`; screen `ui/goals/GoalsPanel.kt:200-204` |
| `odometer_miles` *(exists, unimplemented)* | `vehicles` + `service_records` | `VehicleController.currentMileage` `vehicle/VehicleController.kt:1246-1247` (NOT a DAO - two fields added) | `FleetDigestBuilder.odometerTrendLine` `advisor/digest/FleetDigestBuilder.kt:260-288` over `ServiceRecordDao.getRecentForVehicle` `data/local/ServiceRecordDao.kt:34-35` | **AMBIGUOUS - see flags** | `REPORTED` always, hardcoded, reasoning at `advisor/digest/FleetDigestBuilder.kt:46-52` | Trend yes; **no goal path resolves this key at all** |
| `sleep_minutes_nightly` | `sleep_logs` | `SleepLogDao.forWindow(dayStart, dayEnd)` `data/local/SleepLogDao.kt:24-25` summed by `buildSleepGap` `sleep/SleepGap.kt:69-71` | **would need writing** (loop `forWindow` per night; the DAO supports it) | MAINT (a band) | `REPORTED`, stored `data/local/SleepLog.kt:38`; no gate exists, doc `:9-11` | Current night yes: `BioDigestBuilder.sleepLine` `advisor/digest/BioDigestBuilder.kt:225-241`. No multi-night trend |
| `sessions_per_week` | `workout_set_logs` + `workout_plans` | `buildWeeklyWorkoutGap` `workouts/WorkoutGap.kt:32-47` over `WorkoutSetLogDao.forWindow` `data/local/WorkoutSetLogDao.kt:18-19` and `WorkoutPlanDao.currentPlan` `data/local/WorkoutPlanDao.kt:16-21` | **Already written**, 4 weeks | MAINT (hit the plan; over is not better) | `REPORTED` by construction `data/local/WorkoutSetLog.kt:20-26` | **Yes.** `BioDigestBuilder.sessionsLine` `advisor/digest/BioDigestBuilder.kt:148-171` |
| `calories_daily_kcal` | `meal_logs` + `meal_targets` | `buildDailyMealGap` `meals/MealGap.kt:46-62` over `MealLogDao.forWindow` `data/local/MealLogDao.kt:23-24` | Current week per-day already; longer **would need writing** | MAINT (a band) | `REPORTED` **and the figure itself is an LLM estimate** `data/local/MealLog.kt:15`; wrapped `DigestText.estimate` `advisor/digest/BioDigestBuilder.kt:138` | **Yes.** `BioDigestBuilder.intakeLine` `advisor/digest/BioDigestBuilder.kt:113-140` |
| `protein_daily_g` | `meal_logs` | same `MacroTotals` sum `meals/MealGap.kt:48-53` | same | ACCUM within a day (a floor), MAINT across days | as above, estimate | Computed into `MacroTotals` but **not emitted** - the digest line prints kcal only (`BioDigestBuilder.kt:131`) |
| `lift_max_<exercise>` | `workout_set_logs` | `WorkoutSetLogDao.forExercise` `data/local/WorkoutSetLogDao.kt:49-50`, max per day | Per-day maxima already derived `advisor/digest/BioDigestBuilder.kt:190-194` | ACCUM (a genuine ceiling) | `REPORTED` | Partly: stall detection only, `BioDigestBuilder.stalledLiftLine` `advisor/digest/BioDigestBuilder.kt:182-216` |
| `monthly_spend_cents` | `ledger_transactions` + `budget_targets` | `BudgetVsActual.spentCents` `ledger/LedgerBudget.kt:124` via `LedgerController.budgetVsActual` `ledger/LedgerController.kt:400` | **Already written**, 4 months + trend | REDUC | per-row `combinedTier` `advisor/digest/CredDigestBuilder.kt:194-195` | **Yes.** `CredDigestBuilder.spendLine` `advisor/digest/CredDigestBuilder.kt:142-163`; `monthSpendFrom` `ledger/LedgerBudget.kt:161-169`; `HomeDigestBuilder.credHeadline:127` |
| `category_spend_cents_<cat>` | same | one `BudgetLine.gap.actual` inside the same `BudgetVsActual` | same | REDUC | same | Yes, per category, current month `advisor/digest/CredDigestBuilder.kt:82-91` |
| `grocery_spend_cents` | `pantry_receipts` | `PantryReceiptDao.totalSpendCents` `data/local/PantryReceiptDao.kt:16-17` - **ALL TIME, not a window** | `bucketMonthlySumCents` `ui/pantry/PantryChartData.kt:61-73` over `getAllForCharts` `data/local/PantryReceiptDao.kt:37-38`. Lives in a UI file, no DAO window query | REDUC | Gate-reconciled (`data/local/PantryReceipt.kt:14-18`) but **no `trustTier` column exists on the table** - a goal would assert PROVEN from the gate's existence, never read it | Not in any digest builder; rendered on `PantrySpendPanel` `ui/pantry/PantryRows.kt:129-166` |
| `service_cost_cents` | `service_records` | `ServiceRecordDao.totalCost` `data/local/ServiceRecordDao.kt:52-53` - **ALL TIME, per vehicle** | **would need writing** (`countInRange` `:65-69` exists for COUNTS, no cost-in-range) | REDUC | `REPORTED` (driver-entered) | No |
| `maintenance_overdue_count` | `maintenance_items` | `VehicleController.dueItems` `vehicle/VehicleController.kt:1345-1350`, or `buildDueRows` in the digest | **would need writing** (no historical overdue snapshot is stored) | REDUC toward zero | `REPORTED` | **Yes.** `FleetDigestBuilder.maintenanceLines` `advisor/digest/FleetDigestBuilder.kt:168-181`; `HomeDigestBuilder.fleetHeadline:210` |
| `open_tasks_count` | `list_items` | `ListItemDao.allActive` `data/local/ListItemDao.kt:38-39` filtered, or `openCountForList` `:22-23` | **would need writing** - no history of the count is kept | REDUC | none - `LogDigestBuilder` deliberately stamps no tier, class doc `advisor/digest/LogDigestBuilder.kt:20-23` | **Yes.** `LogDigestBuilder.ageBandLines:117`; `HomeDigestBuilder.logHeadline:236-239` |
| `overdue_reminders_count` | `list_items` | `ListItemDao.missedItems` `data/local/ListItemDao.kt:154-155` | **would need writing** | REDUC toward zero | none, as above | **Yes.** `LogDigestBuilder.overdueReminderLines:135-138` |
| `pid_avg_<pid>` (telemetry) | `obd_samples` | `OdbSampleDao.summarize` `data/local/OdbSampleDao.kt:85-90` - min/max/avg/count over a window, already written | **Already written** (call it per bucket) | depends on the PID | `REPORTED` | No |

## Flags: looks computable, is not

This is the section to act on. Each of these would produce a number that reads as fact and is not.

### 1. `bodyweight_kg` does not return kilograms

`ui/goals/GoalsPanel.kt:205-207` resolves the key by reading `BodyweightLogDao.mostRecent()` and
handing back `log.weightValue` with `log.weightUnit` verbatim. `BodyweightLog.weightUnit` is
`"lbs"` or `"kg"` (`data/local/BodyweightLog.kt:24-26`) and nothing converts. The only converter in
the app is `HomeDigestBuilder`'s private `toKg` (`advisor/digest/HomeDigestBuilder.kt:117`), which
the goal path does not call. A goal stating `targetValue = 80, metricKey = "bodyweight_kg"` against
a driver logging in lbs compares 176 to 80. It survives today only because `MetricResolution.Reading`
prints the unit beside the number and draws no meter (`ui/goals/GoalsPanel.kt:172-176`) - i.e. the
key is wrong but the render is honest. **The moment anything computes a delta or a projection off
this key, it is wrong.** `BioDigestBuilder.weightLine` dodges it a third way, by filtering to the
most recent unit and excluding the others from the average (`advisor/digest/BioDigestBuilder.kt:83`,
doc `:62-70`). Three files, three different mitigations, no shared conversion.

### 2. `odometer_miles` is a lifetime monotonic total, and almost no odometer goal is

Three independent defects, any one fatal:

- **It is an estimate, not a reading.** `currentMileage` = `odometerBaseline + tripMilesSinceBaseline`
  (`vehicle/VehicleController.kt:1245-1247`). `mileageCaveat` (`:1260-1276`) exists specifically to
  attach "estimated, last confirmed N days ago" and explicitly covers the worst case, a car with no
  baseline ever set accumulating pure dead reckoning (`:1268-1276`). A goal figure that drops the
  caveat re-commits the laundering ticket 06 named.
- **It is cumulative.** The common real goal is windowed - "under 10,000 miles this year". Feeding a
  lifetime 143,000 into `accumulationProgress(143000.0, 10000.0)` returns `14.3`, and `DeckMeter`'s
  `coerceIn(0f,1f)` turns that into a full bar, silently.
- **Even as accumulation it has no baseline.** "Reach 100k" is a fraction from an origin the `Goal`
  row does not store; `Goal` has no `startValue` column (`data/local/Goal.kt:68-91`).

Recommendation: do not resolve `odometer_miles`. If a mileage goal is wanted, define
`miles_driven_window` with an explicit window and compute a delta, never the odometer itself.

### 3. Habit adherence off recurring reminders does not exist

`NotesController.tick` refuses outright for a repeating item: `if (item.repeatKind != null) return
false` (`notes/NotesController.kt:122`, doc `:119-120`). A recurring reminder therefore has **no
completion record, ever** - `doneAt` is never written for it. `ListItemSkipDao.skippedDatesForItem`
(`data/local/ListItemSkipDao.kt:13-14`) records only SKIPS. Any metric shaped "how often did I
actually do the recurring thing" is computable only as a count of skips against an assumed
denominator, which is inference about Kevin's life, not a record of it - settled decision 2 forbids
it.

### 4. `tasks_completed_per_week` is not recoverable

`markDone` writes `doneAt` (`data/local/ListItemDao.kt:44-45`), but: no query filters on `doneAt`
(would need writing), and `deleteById` soft-deletes with `deleted = 1` (`:50-51`) while every read
in that DAO carries `deleted = 0`. A task completed and later tidied away vanishes from the count.
A completion rate computed over a shrinking denominator drifts upward for free.

### 5. `savings_balance_cents` sums credit cards into savings, and ignores known pending money

`GoalProgress.savingsBalanceCents` (`goals/GoalProgress.kt:46-61`) takes **every** USD account
(`allAccountIds`, `data/local/LedgerTransactionDao.kt:50-51`) and adds each one's latest
`balanceCents`. `LedgerTransaction` has no account-type column at all (fields at
`data/local/LedgerTransaction.kt:56-90`), so a card account is indistinguishable from a savings
account and is summed in. Two further silent behaviours:

- `latestBalanceCents` requires `balanceCents IS NOT NULL` (`:43-47`), and some statement formats
  print no running balance (`data/local/LedgerTransaction.kt:52-53`). Such an account contributes
  nothing and the total reads complete anyway - no coverage concept guards this figure the way
  `BudgetVsActual.isComplete` guards spend (`ledger/LedgerBudget.kt:110`).
- `provisionalDeltaCentsAfter` (`:170-177`) and `pendingDeltaCents` (`:193-198`) both exist and
  neither is applied here. Money the app knows is in flight is excluded without saying so.

The tier is honest (`hasReconciledRows` -> `combinedTier`); the **scope** is not.

### 6. `grocery_spend_cents` double-counts against ledger groceries

The same money can land twice: once as a `pantry_receipts` row from a photographed receipt, once as
a `ledger_transactions` row categorised into a groceries `Category` and summed into a `BudgetLine`.
Nothing links or dedupes them - `PantryReceipt` has no ledger foreign key
(`data/local/PantryReceipt.kt:21-30`) and no matcher exists. A single "spend less on groceries" goal
must pick ONE source and say which. Related: `BudgetVsActual.spentCents` deliberately **excludes**
uncategorised spend (`ledger/LedgerBudget.kt:113-124`, Kevin 2026-08-15), so a reduction goal read
off it under-reports and owes the `uncategorizedExcludedSentence` disclosure beside it.

### 7. Null-coerced estimates: a logged meal with no calorie guess reads as zero

`mealsToday.sumOf { it.caloriesKcal ?: 0 }` (`meals/MealGap.kt:48`, and identically
`advisor/digest/BioDigestBuilder.kt:131`). `MealLog.caloriesKcal` is nullable
(`data/local/MealLog.kt:31`). A meal logged without an estimate is indistinguishable from a meal of
zero calories, and a "stay under 2,200" goal reads as on-track for it. Same shape as
`ServiceRecord.costCents` being nullable while `totalCost` COALESCEs the sum
(`data/local/ServiceRecordDao.kt:52-53`) - `countWithCost` (`:61-62`) exists precisely because that
gap is known, and no caller of `totalCost` is obliged to consult it.

### 8. Lifts compare across units

`stalledLiftLine` compares `weightValue` across rows with no unit check
(`advisor/digest/BioDigestBuilder.kt:188-205`); `WorkoutSetLog.weightUnit` is **nullable** and free
(`data/local/WorkoutSetLog.kt:36`). Same disease as flag 1, one aisle over. A `lift_max_*` goal
inherits it.

### 9. Telemetry metrics have a hard 365-day floor

`OdbSampleDao.purgeOlderThan` is called with `now - RETENTION_MS`, `RETENTION_MS = 365 days`
(`vehicle/TelemetryRecorder.kt:81`, `:232`). Any PID-based goal older than a year silently loses its
own baseline. `summarize` will happily return an average over a window that has been half-deleted.

### 10. `metric_key` accepts anything the model says

`LiveToolbox.kt:3211` stores `args.optString("metric_key")` unvalidated;
`GoalController.setGoal` (`goals/GoalController.kt:87-131`) passes it through; the schema
(`LiveToolbox.kt:1316-1321`) names three keys in prose but sets no `enum`, while `aspect` on the
same tool DOES (`:1311`). So `metric_key: "sleep_hours"` stores cleanly and resolves nowhere - the
goal renders as prose forever with no signal to anyone that a measurable intent was dropped.
Worse, `GoalController.setGoal` uses `metricKey` equality to decide REVISE-vs-CREATE
(`goals/GoalController.kt:93`), so two spellings of the same intent (`sleep_hours` /
`sleep_minutes`) mint two competing active goals for the same aspect. **The cheapest single fix on
this whole page: give `metric_key` an `enum` sourced from the same constant the resolver switches
on.**

## Prose: things that do not fit a table row

**Direction is not a property of the metric; it is a property of the goal.** `sleep_minutes_nightly`
is MAINT for Kevin and would be ACCUM for someone recovering from deprivation. `monthly_spend_cents`
is REDUC unless the goal is "spend the travel budget". `Goal` stores no direction column
(`data/local/Goal.kt:68-91`), and `accumulationProgress` cannot infer one -
its doc (`goals/GoalProgress.kt:19-22`) says direction-ambiguous metrics must never call it, which
means the caller must know. Today the only caller that knows is a hardcoded `when` on the key
string (`ui/goals/GoalsPanel.kt:199-209`). Widening the key list without carrying direction with it
moves that `when` from two arms to fifteen and makes every future arm a coin-flip. **A direction
enum belongs beside the key list in code (not a migration, same argument `Goal`'s doc makes for
`metricKey` itself at `:29-36`), and `accumulationProgress` should be reachable only from the ACCUM
arm.**

**`Goal` has no baseline column, which caps what "progress" can mean.** For ACCUM toward a ceiling
from ~zero (savings), `current/target` is fine. For REDUC (weight, spend), an honest fraction needs
where the goal started - `(start - current) / (start - target)`. `Goal` stores `createdAt`
(`:89`) so a baseline is *recoverable* for any metric with a timestamped log (read the metric as of
`createdAt`), and is *not* recoverable for `odometer_miles` on a car whose baseline was never set,
nor for any metric with no rows before `createdAt`. That recovery query does not exist for any
metric today.

**The revision trail interacts with baselines badly.** A revised goal inserts a new row with a new
`id` and a new `createdAt` (`goals/GoalController.kt:122-131`, lineage doc `data/local/Goal.kt:42-51`).
Deriving a baseline from `createdAt` therefore silently re-baselines the goal every time Kevin
restates it, which is exactly the "a goal that quietly got easier" case the lineage exists to make
visible. Baseline must read the **lineage's first** row (`GoalDao.history`,
`data/local/GoalDao.kt:43-44`), not the current one.

**Where the trend queries genuinely already exist**, the shape is uniform and worth copying rather
than reinventing: a `forWindow(fromMs, toMs)` on the log table, looped by the caller over N period
buckets, with the bucket boundaries from `weekStartEpoch`/`weekEndEpoch` (`workouts/WorkoutGap.kt:75,
:85`) or `dayStartEpoch`/`dayEndEpoch` (`meals/MealGap.kt:75, :88`). Four tables already carry it:
`bodyweight_logs`, `meal_logs`, `sleep_logs`, `workout_set_logs`. Three do not and would need one
written: `pantry_receipts`, `service_records` (cost, not count), `drives`/`daily_drive_logs` (both
DAOs take only a `limit`, `data/local/DriveDao.kt:18-19`, `data/local/DailyDriveLogDao.kt:24-25`).

**Tier is cheap where the row stores it and expensive where it does not.** `bodyweight_logs`,
`meal_logs`, `sleep_logs`, `workout_set_logs` all store `trustTier` on the row, so
`.map { it.trustTier }.combinedTier()` is one line. `ledger_transactions` derives it from
`ingestMethod`/`categoryPending` and the rule is duplicated in three files that each say so in a
comment (`advisor/digest/CredDigestBuilder.kt:193-195`, `advisor/digest/HomeDigestBuilder.kt:179-182`,
plus `ledger/LedgerBudget.kt`'s private original). FLEET has no tier data at all and hardcodes
`REPORTED` with a written justification (`advisor/digest/FleetDigestBuilder.kt:46-55`,
`HomeDigestBuilder.kt:203-209`). `pantry_receipts` and `list_items` have no tier concept - pantry
because everything passed the gate, LOG because nothing in it is a claim about the outside world
(`advisor/digest/LogDigestBuilder.kt:20-23`). A goal panel rendering tiers across aspects will need
all four of those postures, not one.

**What is free right now.** Every figure in the table's rightmost column marked "Yes" is already
computed by a digest builder that the advisor reads on every brief. A goal metric that reuses one of
those reads costs a resolver arm and nothing else. That is: bodyweight (weekly avg + trend), sessions
per week vs plan, daily kcal vs target, tonight's sleep vs target, stalled lift, monthly spend
(4-month series + trend), per-category spend, overdue maintenance count, open task count, overdue
reminder count. **Ten metrics, already computed, none of them reachable from `metricKey` today.**

## Assumptions ledger

| Claim | Tag |
|---|---|
| Every file:line cited above | **traced** - each file opened and read at the cited lines |
| `research/` was empty before this file; issues 01-08 all `Status: open` | **traced** (directory listing, headers of 01 and 02) |
| `odometer_miles` resolves nowhere | **traced** - `grep -rn metricKey app/src` returned every call site; the only `when` on the key is `ui/goals/GoalsPanel.kt:199-209` (`savings_balance_cents`, `bodyweight_kg`, `else -> null`) and the only other read is `CredDigestBuilder.kt:214`'s equality check on `savings_balance_cents` |
| `metric_key` has no `enum` in the tool schema | **traced** - `LiveToolbox.kt:1316-1321` read in full alongside `aspect`'s `enum` at `:1311` |
| `LedgerTransaction` has no account-type column | **traced** - full field list read, `data/local/LedgerTransaction.kt:56-90` |
| Recurring items can never be ticked | **traced** - `notes/NotesController.kt:122` |
| Direction classifications (ACCUM/REDUC/MAINT) per metric | **reasoned** - read off each metric's semantics against `GoalProgress`'s own doc; not stated anywhere in the repo, and the two marked AMBIGUOUS are ambiguous precisely because the repo does not say |
| Pantry/ledger grocery double-count is possible | **reasoned** - no link column and no matcher found by grep; I did not construct the two rows and observe the double count |
| `accumulationProgress(143000, 10000)` returns 14.3 and the meter clamps to full | **reasoned** from `goals/GoalProgress.kt:31-34` and its own doc's statement that `DeckMeter` does the `coerceIn(0f,1f)`; not executed |
| A null-calorie meal reads as 0 in a goal figure | **reasoned** from `meals/MealGap.kt:48` and the nullable field; no test written |
| "Ten metrics already computed but unreachable from `metricKey`" | **traced** for the ten computations, **reasoned** for the unreachability (follows from the single `when` above) |
| Nothing in this report was built, run, compiled, or installed | read-only investigation, per the brief |
