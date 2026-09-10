---
map: one-home
ticket: "04"
title: "Today's plan retires: which table the advisor's day-list lands in"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Where the advisor's day-list lands

**Kevin, 2026-09-10:** *"today's plan > no need since we have bio to do list. the advisor would tell
me what would be a good daily todo list for workouts > and populate it that way."*

## The thing that is easy to get wrong here

Read as two instructions, this says "delete X" and "build X". The one thing in the codebase called
today's plan is `GoalChecklistPanel` (`CalendarScreen.kt:546`), and what it renders is a daily
workout list written by an advisor. **Deleting it and then building it would be the failure mode of
this ticket**, so state the actual complaint before deciding anything.

## What is really there

`advisor/GoalChecklistSync.kt` materialises today's lines from `mealTargetDao` / `sleepTargetDao` /
`workoutPlanItemDao` into ordinary `list_items` rows, tagged with a string constant
`ITEM_PREFIX = "Plan: "` so it can find its own rows again tomorrow. Ticked through
`NotesController`. Regenerated on app open; no reset job.

Beside it, on the same day view, the calendar renders **a section per recurring checklist** from a
real table: `checklists` + `ChecklistTick`, via `ChecklistController.checklistsForDay` and
`itemsWithTickState` (`CalendarScreen.kt:357-383`).

So the day view has two mechanisms for "a list that comes back every day", one of which is a prefix
hack inside a table meant for one-off items. `one-today` ticket 09 says the same thing in its own
words and calls the prefix out by name.

**That is what "no need" is reacting to.** The capability is wanted; its storage is the problem.

## The decision

**Recommended: the advisor writes a recurring `checklists` row, and `GoalChecklistPanel` is deleted
because the checklist section already renders it.**

Consequences to accept with it:

1. **`GoalChecklistSync`'s prefix mechanism retires.** No `ITEM_PREFIX`, no scanning `list_items` for
   your own rows. The checklist table has identity of its own.
2. **Tick history comes for free.** `ChecklistTick` is per-day and browsable, which is the
   *"end of day it records and resets. i can look back and see what i did"* half of Kevin's
   2026-09-04 quote that `list_items` cannot do.
3. **Existing `Plan: ` rows need a story.** They are live data on the phone. Migrate them into a
   checklist, or leave them to age out as ordinary items and stop generating more. **Leaving them
   half-managed - no longer regenerated but still matched by a prefix nothing owns - is the option
   that must not be chosen silently.**
4. **The meal and sleep lines go too, or they do not.** `GoalChecklistSync` covers more than
   workouts. Kevin named workouts only. Decide explicitly whether meals/sleep follow into checklists,
   stay on the old path, or stop being materialised at all.

## The dependency, stated rather than absorbed

**`one-today` ticket 09 owns the recurring-checklist shape** ("a list you tick every day, and can
look back on", `ready`, unstarted, data layer described as in flight). If 09 changes that table, this
decision changes with it.

Two orders are possible and the choice belongs to Kevin:

- **09 first, then 05 writes into whatever 09 built.** Slower, no rework, and 09 is `ready` anyway.
- **05 first against the table as it stands today.** Faster to something Kevin can use, at the risk
  of a second migration when 09 lands.

Do not decide this by starting to type.

## Not in scope

The advisor's PROMPT - what makes a good workout list - is `aspect-advisors` 04 (the BIO coaching
playbook, resolved) and is not reopened. This is about where the output goes.

## Resolution

Kevin confirms the target table, rules on the four consequences, and picks the order against
`one-today` 09. Then ticket 05 is buildable.
