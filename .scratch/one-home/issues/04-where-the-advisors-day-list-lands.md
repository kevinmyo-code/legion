---
map: one-home
ticket: "04"
title: "Today's plan retires: which table the advisor's day-list lands in"
type: decision
status: resolved
status-detail: >
  Resolved 2026-09-10 by Opus on Kevin's "run everything with your taste".
  The advisor writes a recurring `checklists` row; GoalChecklistPanel and
  GoalChecklistSync's ITEM_PREFIX both retire. Existing "Plan: " rows are
  migrated, not abandoned. Meals and sleep follow workouts into checklists
  - one mechanism, not one and a half. Builds against the checklists table
  as it stands; one-today 09 widens that table, it does not replace it.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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


---

## Resolution, 2026-09-10

**The advisor writes a recurring `checklists` row.** `GoalChecklistPanel` is deleted;
`GoalChecklistSync`'s prefix mechanism retires with it.

The capability is right and only its storage was wrong, which is why this is a re-point and not a
rebuild. `ITEM_PREFIX = "Plan: "` is a string used to find your own rows in a table that belongs to
something else - it cannot survive a user typing "Plan: call mum", it cannot record a tick history,
and it has no identity a foreign key could point at. `checklists` + `ChecklistTick` has all three,
already renders on the day view, and is already ticked through `ChecklistController`.

### The four consequences, ruled

**1. The prefix mechanism goes.** No string matching to identify machine-written rows. The checklist
row is the identity.

**2. Tick history comes with it.** `ChecklistTick` is per-day and browsable, which is the
*"end of day it records and resets. i can look back and see what i did"* half of Kevin's 2026-09-04
quote that `list_items` structurally cannot do. This is a gain, not a side effect, and ticket 05's
device step is where it gets confirmed.

**3. Existing `Plan: ` rows are MIGRATED, not left to age out.** This is the consequence the ticket
flagged as the one that must not be chosen silently, so it is chosen loudly: a one-shot migration
moves live `Plan: `-prefixed `list_items` onto the checklist the advisor now owns, and nothing keeps
generating them. Leaving them half-managed - no longer regenerated, still matched by a prefix nothing
owns - is the option that produces rows nobody can explain in a month.

**Undone rows migrate; already-ticked ones are left alone.** A done row is a record of a day that
already happened, and rewriting history to make a migration tidy is worse than a slightly untidy
migration. Room migration rules apply in full (CLAUDE.md §5): verbatim generated SQL, additive,
`exportSchema`, a migration test, `SCHEMA_VERSION` bumped in lockstep.

**4. Meals and sleep follow workouts into checklists.** Kevin named workouts only, and the temptation
is to move only what he named. That leaves `GoalChecklistSync` alive for two of its three sources -
so the prefix survives, the panel survives, and consequence 1 is not actually done. **A mechanism
retired for two of three reasons is not retired.** One mechanism, or the old one stays and this
decision buys nothing.

### Order against `one-today` 09

**Build 05 now, against `checklists` as it stands.**

09's subject is *user-authored* lists - Kevin typing "3 sets goblet squats" himself. Its data-layer
work widens what a checklist can express; it does not replace the table, and `ChecklistController` /
`ChecklistTick` are what it builds on. So this is not the fork the ticket feared: 05 writes through
the controller, and a controller is exactly the seam that absorbs a schema widening.

**The risk accepted:** if 09 changes the tick semantics rather than the shape, 05 revisits. That is a
smaller cost than blocking a capability Kevin asked for behind a ticket nobody has started.

### Not reopened

The advisor's PROMPT - what makes a good workout list - is `aspect-advisors` 04, resolved. This
decides where the output goes and nothing about what it says.
