---
map: goal-plans
ticket: 02
title: "The recommender, and its editable doctrine"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-training-nutrition-doctrine]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# The recommender, and its editable doctrine

## What to build

A sub-agent that turns a prose goal into concrete targets, primed by a playbook Kevin can audit and
edit.

**Reuse `PrimingTopic` and `PlaybookStore` wholesale** (settled decision 3). A new topic joins the
existing four. That machinery already gives, with no new code:

- per-profile file-backed storage of the driver's own edit,
- the shipped constant as the seed and the thing a revert returns to,
- `requiredPhrases` refusing a save that deletes a safety boundary,
- `PlaybookKeywordsTest` guarding the compile-time constant.

**Do not build a second skills system.** Two stores of editable doctrine is how two subtly different
safety rules end up shipping.

## What it outputs

Targets written through tools that already exist and are already in the advisor's writable-op
allowlist: `set_meal_target`, `set_sleep_target`, `create_workout_plan`, `set_goal`. **Nothing new
gets write access** (settled decision 9).

Structured output, not prose - `SubAgent.askTyped` with a `responseSchema`, the same way
`AdvisorAgent` does it. A plan that cannot be parsed cannot be accepted.

## Honesty

Settled decision 5: **say it is a starting point ONCE, at generation.** *"Starting at 2,300 calories
and 180g protein - worth adjusting once you have two weeks of weight data."* After Kevin accepts, it
is a target he chose and it stops hedging.

CLAUDE.md §4 rule 5 is satisfied at the moment it matters. Repeating "estimated" every day trains
him to stop hearing the word, which is worse than saying it once and meaning it.

## Verification

- Suite green; the schema and the honesty clause both covered.
- The playbook's `requiredPhrases` genuinely refuse an edit that deletes a boundary - test it, do
  not assume the existing mechanism covers a new topic.

---

## Settled while building (Kevin, 2026-08-21)

**The accuracy bar, in his own words:** *"it doesnt need to be very accurate. just a check list of
recommended workouts to loosely follow etc."*

That is the sentence that decides most of what follows. The deliverable is a rough plan a person can
follow, not a prescription. Where a choice was between more accurate and simpler, simpler won.
**It does not loosen the safety boundaries** - a loose plan has more room to be wrong, not less.

| # | Question | Ruling |
|---|---|---|
| 1 | New `PLAN` topic or grow `BIO`? | New topic, but they split what they own. `PLAN` owns plan SHAPE (targets, checklist, hedging, refusals); `BIO` stays the authority on the NUMBERS and `PLAN` does not restate them. The recommender is primed with both. Two editable copies of one protein figure is the failure mode this avoids. |
| 2 | Overlap with `create_workout_plan` | The recommender CALLS it. It does not program workouts itself and does not replace `WorkoutController.generatePlan`. |
| 3 | Protein denominator (the contested one) | **Total bodyweight.** Always computable. `BIO` may note the lean-mass reading exists; the recommender does not use it. |
| 4 | Body fat | Never asked for, never guessed. Nothing in the generation path takes it. |
| 5, 6 | Maintenance calories, activity multiplier | Mifflin-St Jeor plus a stated multiplier. Both are guesses and the playbook says so once. No error bands. |
| 7 | Hedge once vs label every time | Once, at generation. CLAUDE.md §4 rule 5 is satisfied at the moment it matters; daily re-hedging trains him to stop hearing it. |
| 8 | The "adjust in two weeks" promise | **Nothing watches for two weeks of weight data, so it must not promise to.** Phrased as an invitation, never a commitment - the same posture as §7's outcome-verb rule. |
| 9 | A target that crosses a boundary | Refused INDIVIDUALLY. The rest of the plan still generates, and it says plainly which target it refused and why. |
| 10 | A medical condition in the goal prose | Refuse the affected target only, keep the rest, point at a professional. The app does not pick a clinical target. |
| 11 | Where refusals live | **Both, deliberately.** General boundaries in playbook prose guarded by `requiredPhrases`, auditable and editable. The one the doctrine calls medically supervised - a calorie target at or below 800/day - is a **hard floor in Kotlin**, because a playbook edit must not be able to weaken it and a substring check cannot catch a rewording that keeps the words and drops the meaning. |
| 12 | "each aspect" - cred and log too? | **BIO-only for now.** Not generalised. |
| 13 | Plan lifetime | No expiry, no regeneration cadence. It sits until it is argued with (ticket 03). |
| 14 | Consent shape | Accepting a plan is ONE consent, not one per target. |
