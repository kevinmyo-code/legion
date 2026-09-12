---
map: chief-of-staff
ticket: "05"
title: "One outstanding view: what needs doing, across checklists, reminders and tasks"
type: build
status: built
status-detail: >
  Built 2026-09-12. outstanding/Outstanding.kt holds the ranking as pure,
  tested functions; OutstandingController only fetches, from the three stores
  that already exist. Ranked by when a thing stops being possible, never by
  which table it came from: overdue first (most overdue leading), then dated
  soonest-first, then undated, then today's already-ticked lines last so a
  finished day reads as finished. A checklist line is never overdue - it
  resets tonight, so it can only be unticked. 3518 tests, 0 failures. Owes a
  caller: nothing reads it yet, and a device run.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# One outstanding view

**Kevin, 2026-09-12:** *"my stuff that needs doing, errands"*. He asked for one view and there are
three stores behind it:

| Store | Holds |
|---|---|
| `checklists` + `ChecklistTick` | Recurring lists, ticked per day - the bio plan, groceries |
| `list_items` | Reminders, one-off todos, with dates and place triggers |
| `events` where `kind = TASK` | Deadlines with a moment - coursework, anything imported |

Nothing asks all three at once. `HomeDigestBuilder` reads aspect digests, which is a different
question - it answers *how is each area doing*, not *what is outstanding right now*.

## Build

A single read that answers "what needs doing", ranked, across all three - and one surface for it.

- **Ranked by when it stops being possible**, not by store. A deadline tonight outranks an errand
  with no date, which outranks a recurring item already ticked today.
- **Say which store a thing came from** only where it changes what the user can do about it. A
  checklist line resets tomorrow; a deadline does not. That difference is real and belongs on screen.
- **Overdue is included, not hidden.** Same ruling `web-surface` 02 made for the web: a thing past
  its date is still work, and dropping it quietly is the same class of lie as rendering a failed read
  as an empty day.
- **This is the advisor's input too.** Whatever this returns is what a chief-of-staff answer to
  "what should I be doing" should be built from, rather than each advisor re-deriving it.

## Why this is the cheapest real step on the map

It needs no ruling, no new table and no new aspect. Every row it reads already exists and is already
synced. It is the one ticket here that turns Kevin's sentence into something usable this week.

## Verification

- Against real data on the A25, not a fixture: the count it reports has to match what the three
  stores actually hold on the day it runs.
- The overdue case explicitly - there was one on 2026-09-12 (WK02: Bio Video Presentation, MKTG 3303)
  that the phone showed nowhere.
