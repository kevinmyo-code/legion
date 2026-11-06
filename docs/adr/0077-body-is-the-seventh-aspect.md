---
status: accepted
decided: 2026-11-05
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-05 - The fat-loss loop; Body is the seventh aspect (Kevin)]]"
tags: [adr]
---

# 77. Body is the seventh aspect

## Standing

ACCEPTED 2026-11-05. Map `.scratch/fat-loss/`. CLAUDE.md section 1 lists seven aspects.

## Context

CLAUDE.md section 1 listed six domains (fleet, ledger, pantry, notes, dates, places) and said a
seventh is a ruling, not a refactor. Body (bodyweight, meals, sleep, workouts and the BIO goals)
has had its own tab, tables, advisor and plan generator since August without being on that list,
so documents disagreed about whether it was an aspect at all (the 2026-08-24 cutover said Body had
"no engine-native aspect yet"). The fat-loss loop adds a profile, an energy estimate, a weight
trend and proposals to it. Kevin, 2026-11-05, to the question whether Body is the seventh aspect:
*"10 ok"*.

## Decision

- **Body is an aspect** with the six others. Its tables are on the engine; its business rules
  (energy estimate, trend, adjustment proposals, the weekly line's facts) live in Django once
  (`server/body_coach/`), and the phone reads them.
- **Its rows are household-shared**, like the other aspects' hand-made rows (ADR 0052), because
  only Kevin logs body data (Kevin, 2026-11-05, *"8 no"* to his wife using it). **This rests on that
  ruling**: before a second member logs weight, meals or waist, those tables need an owner column.
  The body profile (height, sex, birth year, activity) is one row per member, private to that
  member, from the start.
- **What reaches a model:** weight, steps, sleep and daily totals may go to the engine and Gemini.
  Heart rate never does.

## Consequences

- The section 1 list is seven, and an eighth is again a ruling.
- Everything on Body that is not measured (calorie and protein figures, maintenance, a suggested
  target) is an estimate and says so (CLAUDE.md section 4 rule 5).
- No Health Connect, scale or watch integration until Kevin has one (parked in the map).

## Amendment, 2026-11-06: weekly weigh-ins, kcal and protein only, coach tone

Kevin's Body interview (`decisions.md` 2026-11-06). **Weigh-ins are weekly**; no waist, no photos
(the waist log stays in the schema, unasked). **The calorie adaptation needs four weekly points**:
a 28-day window with a weigh-in in each of its weeks and 22 days of logged intake, where it was 14
days. **Targets are kcal and protein only**; carbs and fat are shown when known and never targeted.
Alfred is coach-style (short, the reason in one clause). Unprompted, only three things: an evening
protein shortfall, the Monday check-in (weigh-in first, then trend, sessions done, next week's meal
plan and grocery list), and plan adjustment proposals; never a workout-due or "you haven't
trained" nudge. An off day is one neutral line. Supplements: protein powder and creatine only,
never fat burners. Map `.scratch/body-coach-2/`.

## Amendment, 2026-11-06: one plan, locked in; Alfred sets the target

Kevin: *"basically formulating a plan, working together with alfred, then locking it in and making
adjustments as i go with alfred is the goal"*, and *"i want alfred to set my calorie target"*.
**A target and a programme go live only by locking a version of the member's plan**
(`body_plans`, private to its member; `server/body_coach/plans.py`). Alfred drafts it, setting the
kcal and protein target from the engine's estimate at 0.5% of bodyweight a week for "get lean, keep
muscle" while lifting; Kevin locks it in. **Every later change is a proposal** (the four-weigh-in
adaptation, a stall or deload, "my knee hurts") whose acceptance makes the next version with a
one-line reason. A target he sets himself stays his until he says "let Alfred set it". The goal
plan generator no longer writes a meal target.

