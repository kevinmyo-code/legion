---
map: goal-keeping
ticket: 08
title: What else in the app's own records can be computed deterministically
type: research
status: resolved
status-detail: "2026-08-18, research subagent"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What else in the app's own records can be computed deterministically

## Question

`metricKey` has three known values - `bodyweight_kg`, `savings_balance_cents`, `odometer_miles` - and
widening the list is a code change, never a migration (`Goal`'s own doc, confirmed against
`app/schemas/`). Every metric this finds is a goal that gets measured instead of asked about, which
directly shrinks the check-in path.

**This is a repo investigation, not a web one.** The question is what the app ALREADY stores that a
deterministic function could turn into a current value and a trend.

Investigate and report:

1. **Every table in `data/local/` that holds a time-series a goal could point at.** Meals, sleep,
   workouts, bodyweight, ledger transactions, budget targets, grocery spend, service records,
   odometer readings, drives, telemetry samples.
2. **For each: is a CURRENT VALUE computable** with a query that already exists or an obvious one,
   and is a TREND computable over a window. Name the DAO method where one exists.
3. **Direction.** Accumulation (savings), reduction (weight, spend), or maintenance (sleep hours,
   sets per week). `GoalProgress.accumulationProgress` only serves the first, and its own doc says
   direction-ambiguous metrics must never call it.
4. **Trust tier.** Which of these rest on reconciled data and which on `REPORTED` rows. A goal
   measured off unverified data must say so.
5. **What the existing digest builders already compute**, in `advisor/digest/`. Anything already
   computed there is free.

Report as a table of candidate `metricKey` values with, for each, the source table, the query, the
direction, the trust tier, and whether it is already computed somewhere. Flag any that look
computable but are not - a metric that silently reports nonsense is worse than one that is absent.

## Answer

**Resolved 2026-08-18.** Full report, with file:line for every claim and its own assumptions ledger:
[research/08-computable-metrics.md](../research/08-computable-metrics.md). Gist below; the report is
the authority.

**Roughly 16 candidate metrics catalogued. Ten are ALREADY computed** by a digest builder on every
advisor brief and are simply unreachable from `metricKey`: bodyweight weekly average and trend,
sessions per week against plan, daily kcal against target, tonight's sleep against target, a stalled
lift, monthly spend as a four-month series with trend, per-category spend, overdue maintenance count,
open tasks, overdue reminders. Each costs a resolver arm and nothing else.

**Three keys are documented; two are implemented; one of those two is unit-unsafe.** The only `when`
over `metricKey` is `ui/goals/GoalsPanel.kt:199-209`.

### The four findings that change other tickets

1. **`bodyweight_kg` does not return kilograms.** `GoalsPanel.kt:205-207` returns the logged value
   and its logged unit unconverted; the only `toKg` in the tree is `HomeDigestBuilder.kt:117` and the
   goal path never calls it. It survives today only because that variant draws no meter. Any delta or
   projection built on this key is wrong. **Ticket 01 must not assume a metric key implies a unit.**
2. **`odometer_miles` resolves nowhere at all**, and should not simply be wired up: it is a lifetime
   monotonic dead-reckoned estimate (`VehicleController.kt:1246-1247`, with `mileageCaveat` at
   `:1260` existing to say so). "Under 10k this year" against 143,000 miles gives
   `accumulationProgress` = 14.3 and a silently full bar, because `Goal` has no `startValue`. The
   report recommends a windowed `miles_driven_window` instead.
3. **`savings_balance_cents` sums credit cards into savings.** `LedgerTransaction` has no
   account-type column (`LedgerTransaction.kt:56-90`) and `GoalProgress.kt:46-61` takes every USD
   account. Accounts whose statements print no running balance contribute nothing, with no coverage
   guard. The trust tier is honest; the scope is not.
4. **`metric_key` is unvalidated free text from the model** - `LiveToolbox.kt:3211`, schema at
   `:1316-1321` with no `enum`, while `aspect` five lines earlier has one. `GoalController.kt:93`
   then uses `metricKey` equality to decide revise-versus-create, so `sleep_hours` and
   `sleep_minutes` mint two competing active goals for one intention. **Cheapest single fix on the
   page, and it belongs to ticket 02.**

### Two structural findings

- **Direction is a property of the GOAL, not of the metric**, and `Goal` stores no direction column.
  Widening the key list without carrying direction turns `GoalsPanel`'s two-arm `when` into fifteen
  coin flips. Direction belongs in code beside the key list, on the same non-migration argument
  `Goal.kt:29-36` already makes for `metricKey` itself. **Ticket 01 item 3 now has its answer's
  shape.**
- **A baseline must read the lineage's FIRST row**, not `createdAt` on the current one. Otherwise
  every restatement silently re-baselines - which is exactly the "goal quietly got easier" case the
  revision lineage exists to expose. **Ticket 01 item 5.**

### Also flagged, lower stakes

Recurring reminders can never be ticked (`NotesController.kt:122`), so habit adherence is not
computable at all, only skips. `tasks_completed_per_week` is unrecoverable (no `doneAt` query, and
soft-delete shrinks the denominator). Pantry and ledger grocery spend double-count with no link
column. `meals/MealGap.kt:48` coerces a null to zero, so an uncalculated meal reads as 0 kcal - the
same shape as nullable `ServiceRecord.costCents`. Lifts are compared across units. Telemetry purges
at 365 days, which floors any long-window vehicle metric.

**Nothing was built or run.** The arithmetic claims (the 14.3 fraction, the 0-kcal coercion) are
`reasoned` from the code, not executed.
