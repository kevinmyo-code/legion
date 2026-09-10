---
map: one-home
ticket: "05"
title: "An advisor proposes the day's workout list and it lands as a recurring checklist"
type: build
status: open
status-detail: ""
blockers: ["04"]
blocked-by: ["[[04-where-the-advisors-day-list-lands]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# The advisor writes a checklist

**Kevin, 2026-09-10:** *"the advisor would tell me what would be a good daily todo list for workouts
> and populate it that way."*

## What already exists, so that nothing is rebuilt

The propose-accept-write path is built and resolved (`aspect-advisors` 03, 13, 18). Advisor output
does not reach the database directly - it goes through **`advisor/AdvisorProposalExecutor.kt:38`**, an
allowlisted write door. Handlers today: `setGoal`, `setMealTarget`, `setSleepTarget`,
`createWorkoutPlan` (writes `WorkoutPlan` / `WorkoutPlanItem`), `setBudget`, `setMaintenanceItem`,
`setReminder`, `addTask` (line 292, writes a one-off into `NotesController.addItemDue`).

**There is no handler that writes a recurring checklist.** That is the entire gap. Everything else -
the harness, the BIO playbook, the digest builders, the accept step - is done.

## The build

1. **One new handler on `AdvisorProposalExecutor`**, in the existing allowlist style. It creates or
   updates a recurring checklist and its items, in the table ticket 04 names. It is a handler, not a
   new door: **nothing may write checklists from advisor code except through the executor.** The
   allowlist is the whole point of that class - a second writer beside it makes it decorative.
2. **Idempotence, and it is the hard part.** The advisor runs again tomorrow, and the day after.
   Decide and pin by test what a second proposal for an existing checklist does: replace the items,
   append, or refuse. `GoalChecklistSync` solved this with a string prefix and that is what ticket 04
   retires - the replacement must identify the checklist it owns by a real key, not by matching text.
   **A handler that duplicates the list every morning is the defect this bullet exists to prevent.**
3. **Accepting is Kevin's step, not the model's.** The propose-accept protocol already works this way
   and it is not loosened here. A proposal that writes on arrival is not a proposal.
4. **The tick path is untouched.** `ChecklistController.tick` / `untick` already work and the calendar
   already renders a section per recurring checklist. If this handler is right, **no UI work is
   needed for the list to appear** - it shows up because a checklist exists. If UI work turns out to
   be needed, that is a finding worth reporting, not a scope creep to absorb quietly.
5. **`GoalChecklistPanel` and `GoalChecklistSync` retire** per ticket 04, including whatever 04 ruled
   about the existing `Plan: ` rows and about the meal/sleep lines. Do exactly what 04 said. If 04
   left something ambiguous, stop and surface it (CLAUDE.md §8) rather than choosing.

## Rules that bind this one

- **ADR 0035**: whatever voice tool triggers the proposal needs a hands path. Check whether one
  exists (`settings/playbooks` and the GOALS panel are the neighbourhood) and say plainly what was
  found. If the hands path is the checklist appearing and being tickable, that satisfies 0035 - a
  tool that only reads and speaks is already satisfied by the screen that renders its data.
- **CLAUDE.md §7, estimates**: an advisor's suggested set/rep count is a model's opinion, not a
  measurement. It is not gated by §4 because it states no total, but it must never be rendered as if
  it were a prescription Kevin recorded himself. Check how the existing goal lines are labelled and
  carry the same treatment.
- **§7, outcome verbs**: the tool result must say in words what did NOT happen when the write fails,
  so the assistant cannot claim it added a list it did not add.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML under `app/build/test-results/`.
- A test for the second-proposal case (bullet 2) - run the handler twice and assert the outcome 04
  chose. This is the one that would otherwise be found on the phone in a week, with fourteen copies
  of the same list.
- A test that the executor refuses an unlisted action, unchanged - confirm the allowlist still bites.
- A migration test if 04's answer added a column or table (CLAUDE.md §5: verbatim generated SQL,
  additive, `exportSchema`, migration test, `SCHEMA_VERSION` bumped in lockstep).
- On the phone: ask the advisor for a workout list, accept it, see it on HOME, tick an item, confirm
  it comes back tomorrow with the ticks cleared and yesterday's result still readable. **That last
  clause needs a real overnight or a clock change and cannot be claimed from a unit test.**
