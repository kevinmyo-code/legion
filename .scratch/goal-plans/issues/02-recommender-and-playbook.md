---
map: goal-plans
ticket: 02
title: "The recommender, and its editable doctrine"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-training-nutrition-doctrine]]"]
open-blockers: 1
ready: false
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
