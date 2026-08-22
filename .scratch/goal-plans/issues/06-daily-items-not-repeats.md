---
map: goal-plans
ticket: 06
title: "A day's items you can actually tick"
type: build
status: built
status-detail: "One-off items, idempotent materializer, 14-day window, real completion record. Owes the on-phone run: tick by voice, tick by hand, survive a restart."
blockers: ["04"]
blocked-by: ["[[04-checklist-and-surfaces]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# A day's items you can actually tick

## Why this exists

Ticket 04 shipped the checklist onto Body and Home and then discovered its own premise was false: a
recurring `ListItem` **cannot be ticked**. `NotesController.tick` refuses one outright, deliberately
(notes-lists-calendar ticket 04, Kevin 2026-08-07: *"a repeat is an event you attend, not a chore
you complete"*). So what shipped is followable and skippable, and "tickable" is the word both Kevin
and ticket 04 used.

**Kevin ruled 2026-08-22: daily items, not repeats.** He chose this over leaving it skip-only and
over reversing the 2026-08-07 decision. It is the option that gets real ticking and a real
completion record **without reversing anything** - a one-off item ticks today, with no new mechanism.

## What to build

1. **Plan items become ordinary one-off `ListItem`s**, materialized for a given day. Not repeats.
   `repeatKind` stays null, which is precisely what makes them tickable.
2. **A materializer that runs on app open**, and on acceptance for the current day. **Idempotent** -
   opening the app five times in a day must not produce five copies. Key off the day plus the item's
   identity, not off a count.
3. **Ticking is the existing path.** `manage_item` and `NotesController.tick` already work on a
   one-off item. **Do not add a ticking tool or a second tick path** - that rule from ticket 04 is
   unchanged and is now satisfiable rather than aspirational.
4. **Adherence becomes truthful**, because a ticked item carries a real done timestamp. Still
   **shown, never scored** - CLAUDE.md §7 bans streaks and rankings permanently. Which days had
   items completed is a record; grading him on it is not.

## The retention call, made here so nobody has to guess

Plan items accumulate. Deleting the un-ticked ones would destroy the denominator - "what was due"
- and leave only what was done, which reads as perfect adherence forever. That is the same class of
lie as a zero standing in for "unknown".

**So: plan items live in a rolling window and are removed together, ticked and un-ticked alike**,
following `CONVERSATION_AUDIT_RETENTION_DAYS`' precedent rather than inventing a new convention.
Inside the window both are present, so adherence can show what was due and what was done. Outside
it, nothing is claimed at all.

## Verification

- Suite green **both** ways: `./gradlew testDebugUnitTest` and `./gradlew testDebugUnitTest -Pnokey`.
- The materializer is idempotent across repeated runs in one day - tested, not reasoned.
- A ticked plan item is genuinely tickable through the existing path, and its done timestamp is what
  adherence reads.
- An empty or unaccepted plan still reads "no plan yet", never zero.
- On the phone: tick one by voice, tick one by hand, confirm both land and survive an app restart.
