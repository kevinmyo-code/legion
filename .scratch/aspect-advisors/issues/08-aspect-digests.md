---
map: aspect-advisors
ticket: 08
title: Aspect digests
type: grilling
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-advisor-contract]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Aspect digests

## Question

What deterministic digest does each advisor receive? Per aspect: which numbers (targets, actuals,
gaps, trends, goals), over what window, at what size (the digest is prompt tokens on Kevin's key,
per question). BIO: weight trend, intake vs target, sessions vs plan, per-exercise progression.
CRED: budget-versus-actual per category, provisional/unreconciled coverage stated in words, goal
progress. FLEET: maintenance due (miles/date), recent OBD anomalies. LOG: open tasks, overdue
reminders, calendar horizon. Also: who builds it (existing controllers vs a new digest layer),
how UNRECONCILED/estimate provenance is carried into the digest so the advisor's words inherit
the labels, and what an empty domain reads as ("not logged", never zero).

## Answer

Grilled with Kevin, 2026-08-13 (batched at his request). Four calls, plus the parts that follow
from existing law rather than taste.

**1. A per-aspect `DigestBuilder` in `advisor/`**, five of them, each calling existing
controllers and DAOs read-only. Advisor concerns stay out of the domain controllers, and there is
exactly one place to audit what a question costs. Matches the one-harness-five-briefs contract:
new advisor = new builder.

**2. Compact labelled text on the wire**, not JSON:
`BUDGET groceries target 400.00 actual 312.45 remaining 87.55 [proven]`. JSON spends a real share
of every digest on braces, quotes and repeated keys; text reads naturally to the model and is
eyeballable in a log when advice goes wrong. Savings vs equivalent JSON estimated at ~30-40%,
**not measured** - the token-budget ticket should confirm it rather than inherit it.

**3. Window: current period + 3 prior, then trends.** Four periods of detail (months for CRED and
FLEET, weeks for BIO and LOG) answers "is this getting better or worse"; anything older arrives
as a single precomputed trend figure, never rows. Cost is bounded and does not grow with history.

**4. Aggregates plus a few exemplars.** Per-category totals and gaps, plus a handful of named
outliers - the biggest merchants, the exercise that stalled, the overdue maintenance item. Enough
to be specific without shipping the whole ledger into a prompt on every question.

### What follows from law, not taste (stated, not asked)

- **Every figure carries its tier.** `plan/Plan.kt` already gives `TrustTier` and
  `combinedTier()` (traced), and one reported actual makes the whole gap reported (legion-shape
  D5). The digest tags each figure, so the advisor's words inherit the label instead of the
  advisor guessing. Any figure touching `UNRECONCILED` rows is marked unverified **in words**
  (CLAUDE.md §4 rule 7), and macro/estimate figures are marked estimate (§4 rule 5).
- **An empty domain reads "not logged", never zero.** Legion-shape's meals decision and the
  cyberdeck BIO surface both already hold this line; a digest that reported 0 kcal for an
  unlogged day would have the coach scolding Kevin for a day he simply did not record.
- **Missing coverage is stated.** CRED's digest says when statement coverage has gaps, matching
  the budget-versus-actual decision's "missing coverage stated in words".

### Per-aspect contents (spec for the build tickets)

- **BIO** - bodyweight weekly averages (never daily, per the playbook) + trend; intake vs
  `MealTarget` per day with unlogged days named; sessions done vs `WorkoutPlan`; per-exercise
  progression with the stalled lift named; sleep hours vs `SleepTarget`.
- **CRED** - budget vs actual per category with remaining; uncategorised spend as its own loud
  bucket; provisional/`UNRECONCILED` rows counted and marked; coverage gaps; top merchants;
  goal projections for any `metricKey`-bearing goal.
- **FLEET** - maintenance due by whichever comes first, miles or date, with `neverDone` items as
  overdue-now; recent DTCs with severity tier; odometer trend; last service dates.
- **LOG** - open tasks by age band; overdue reminders; the calendar horizon (read-only, Google
  owns appointments); repeated-deferral flags, which the playbook reads as a signal.
- **HOME** - deferred to the cross-aspect advisor ticket, which decides whether it takes the four
  digests raw or their summaries.

Every digest also carries the aspect's **goals** (statement + progress where a `metricKey`
exists) and the **last ~3 advice-log exchanges** (gist + proposal + outcome), per the contract
and the goal store.

Assumptions ledger: `plan/Plan.kt`'s `PlanGap<T>`/`TrustTier`/`combinedTier()`, and that no
digest layer exists today - **traced** (read the file, grepped for existing summary builders).
The ~30-40% JSON-vs-text saving - **estimated**, flagged for the token-budget ticket to confirm.
Per-aspect contents - **reasoned** from the four playbooks and the decided window/granularity.
Everything else - Kevin's decisions, recorded live.
