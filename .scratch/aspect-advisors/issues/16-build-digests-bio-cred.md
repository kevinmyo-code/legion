---
map: aspect-advisors
ticket: 16
title: "Build: BIO and CRED digest builders"
type: task
status: resolved
status-detail: ""
blockers: ["11", "13"]
blocked-by: ["[[11-token-latency-budget]]", "[[13-build-goal-store]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: BIO and CRED digest builders

## Question

Implement two of the five `DigestBuilder`s from [Aspect digests](08-aspect-digests.md), in
`advisor/`, read-only over existing controllers and DAOs.

**Format**: compact labelled text, not JSON
(`BUDGET groceries target 400.00 actual 312.45 remaining 87.55 [proven]`).
**Window**: current period + 3 prior (weeks for BIO, months for CRED), older history as one
precomputed trend figure. **Granularity**: aggregates plus a few named exemplars, never raw rows.

- **BIO** - bodyweight WEEKLY AVERAGES (never daily, per the playbook) + trend; intake vs
  `MealTarget` per day with unlogged days named; sessions done vs `WorkoutPlan`; per-exercise
  progression naming the stalled lift; sleep vs `SleepTarget`.
- **CRED** - budget vs actual per category with remaining; uncategorised spend as its own loud
  bucket; provisional/`UNRECONCILED` rows counted and marked; coverage gaps stated; top
  merchants; goal projections for any `metricKey`-bearing goal.

Both also carry that aspect's goals and the last ~N advice-log exchanges.

**Non-negotiable, and the reason this ticket has tests:** every figure carries its `TrustTier`
(reuse `plan/Plan.kt`'s `combinedTier()`, do not reimplement); `UNRECONCILED`-touching figures
are marked unverified IN WORDS (§4 rule 7); macro figures marked estimate (§4 rule 5); and **an
empty domain reads "not logged", NEVER zero** - a digest reporting 0 kcal for an unlogged day
would have the coach scolding Kevin for a day he simply did not record.

Verification: unit tests for the empty-domain wording, tier propagation, and digest size against
ticket 11's ceiling.

## Build report

Built 2026-08-13. `advisor/digest/BioDigestBuilder.kt`, `CredDigestBuilder.kt`, 15 tests.
`DigestText` untouched, no shared helpers added to the package (a sibling agent was building the
other three concurrently).

**BIO**: WEIGHT weekly averages wk0..wk-3 + trend (never daily); INTAKE for the current week with
unlogged days NAMED, whole line marked `estimate` because `MealLog` calories are always LLM
guesses; SESSIONS reusing `buildWeeklyWorkoutGap`; LIFT naming one stalled exercise; SLEEP reusing
`buildSleepGap`; GOAL rows with no tier, since a goal carries none by design.

**CRED**: BUDGET per category via `LedgerController.budgetVsActual` with `hasProvisionalRows`
marked `unverified`; UNCATEGORIZED as its own loud bucket; PROVISIONAL count of `UNRECONCILED`
rows; COVERAGE gaps named by account; SPEND as a 4-month aggregate plus trend rather than four
months of per-category rows (a deliberate size choice); MERCHANTS top 3; GOAL progress computed
only for `savings_balance_cents`.

**The empty-vs-zero distinction is tested in both directions**, which is the part that matters:
an empty DB reads `notLogged()` throughout, AND a genuine verified zero (fully categorised spend,
coverage present) still prints `0.00`. Gating "not logged" on `coverage.isEmpty()` rather than
`lines.isEmpty()` is what makes that work.

### Approximations documented rather than hidden
- Merchant grouping uses an unpadded month window; `LedgerController`'s real transfer-pairing
  padding is private to that file. A transfer near a month boundary could be misclassified in the
  MERCHANTS line only - not a gate-critical figure.
- Stalled-lift detection (trailing sessions with no new day-max PR, threshold >=2) is the agent's
  own design, not specified by any ticket.
- Bodyweight weekly averaging keeps only the most-recently-used unit rather than converting.

### Unmeasured, carried to the ship pass
Digest output was **never run through a real tokenizer**. Ticket 11's ~186 (BIO) and ~293 (CRED)
were constructed proxies. Named as a gate on [Ship pass](20-ship-pass.md).

Verification (orchestrator re-run): compile green, **874 tests / 0 failures** from JUnit XML;
`BioDigestBuilderTest` 7/7, `CredDigestBuilderTest` 8/8.
