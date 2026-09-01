---
map: one-today
ticket: "03"
title: "What I crossed off, what is left, what is tomorrow"
type: build
status: resolved
status-detail: "Answered 2026-09-01 by the calendar-home cutover (06c1d3f): the day view splits Yet to do / Done, and any day - including tomorrow - is one tap on the month grid. All three of Kevin's questions now have one surface. Seen on the A25."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# What I crossed off, what is left, what is tomorrow

**Kevin, 2026-08-30:** *"i wanna be able to cross off things ive done and look back at my day and see
how much ive crossed off, and what i need to cross off tomorrow etc."*

Three questions, and the app currently answers one of them.

| Question | Today |
|---|---|
| What do I need to do? | Partly - HOME shows the NEXT entry only, one row. LOG shows all of today. BIO's checklist is a third place. FLEET's due count is a fourth |
| What have I done? | **Nowhere**, except `GoalChecklistPanel`'s 7-day completion strip for plan lines |
| What is tomorrow? | Nowhere. Every surface is scoped to today or to a month grid |

## The data is already there

`done`/`doneAt` on `events` and `list_items`. `missedAt` for the ones that lapsed. `dueAt` on 272
engine records. `workout_plan_items` for the day's exercise lines. Nothing needs a new column - this
is a reading problem, not a storage one.

## What to build

**One pane, three states, on HOME.** Yet to do / done / missed, for a chosen day, defaulting to
today, with tomorrow one step away.

- **Crossing off happens where you look**, not on a detail screen. `GoalChecklistPanel` already does
  this correctly - a real `Checkbox` writing through - and is the pattern to copy.
- **A count that means something.** "6 of 9" is the answer to "how much have I crossed off"; a bare
  list is not.
- **Tomorrow is a peek, not a second screen.** Kevin asked what he needs to cross off tomorrow, which
  is a preparation question, not a planning one.

## Reuse, do not restate

`advisor/digest/HomeDigestBuilder` already computes one headline per aspect across BIO/CRED/FLEET/LOG
and feeds only a Gemini prompt. `ui/TodayGapResolvers` **restates three of those computations** for
the tiles, with doc comments saying so.

**Promote the builder to feed the screen and delete the restatements.** Adding a third implementation
of "what does BIO owe today" would be the actual failure here, and it is the easy mistake to make
because both existing ones are already there and both already work.

## What this replaces

If this lands, HOME's one-row hero, LOG's separate today pane, and BIO's checklist are three views of
one answer. **Say which of them survives** before building - the audit found four surfaces answering
this and the point is to end with fewer, not five.

## Not in scope

The month grid stays where it is. This is the day, not the calendar.
