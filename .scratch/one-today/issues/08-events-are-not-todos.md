---
map: one-today
ticket: "08"
title: "An event passes. A task gets done. They are not the same row."
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# An event passes. A task gets done. They are not the same row.

**Kevin, 2026-09-01:** *"we need to split events and actual todos, i dont mark an event done, it
just passes whether or not i do it, like classes. and assignments tasks > should read from canvas if
done or not and auto tick, this might be a backend supabase thing"*

## The defect this names

The 2026-09-01 calendar-home cutover made every row in the day view tickable. That is wrong for
most of them. A class happens at 09:30 whether or not you engage with it; ticking it means nothing,
and **not** ticking it makes the day read as incomplete when nothing was missed.

All 261 imported rows arrived as `kind = appointment`, and that discriminator does not capture the
thing that actually matters: **does this row have a completion state at all.** Two different kinds
of thing are sitting in one table:

| Row on the real device | Actually |
|---|---|
| COSC 4320 Software Engineering | Event - passes |
| COSC 3334 Intro to Cybersecurity | Event - passes |
| Lin's birthday | Event - passes |
| MATH 3391 - Discussion post due | Task - completed |
| MATH 3391 - WebAssign homework due | Task - completed |
| MATH 3391 - Chapter 1 Quiz due | Task - completed |

**This reframes [[02-ticking-an-appointment]].** That ticket's premise was *"you cannot cross off a
calendar item, and the field is not what is missing"* - right that the field was not missing, wrong
about the fix. The answer is not to make appointments tickable. It is that half those rows were
never appointments.

## Decided 2026-09-01 (Kevin)

1. **Every already-imported row becomes an EVENT.** No heuristic classifier over titles. Guessing
   from the word "due" or "quiz" on real data either invents a chore or hides one, and
   `"COSC 3334 Exam review session"` is a class that matches `exam`. Google-imported rows keep only
   what Google actually knew: when a thing happens.
2. **Canvas becomes the source of truth for assignments**, arriving as tasks carrying real
   submission state. Separate ticket - see below.
3. **An event carries NO state.** No checkbox, no attended flag, no skipped marker. It greys by time
   alone. Kevin, on the alternative: rejected.

## Build

**The kind axis becomes completable-or-not.** `events.kind` is TEXT with no CHECK
(CLAUDE.md §5: widening a TEXT-stored enum is not a migration), so the values widen cheaply. What is
NOT cheap is the data change, and that is the real work here.

- `event` - passes. **Not completable.** Every current `appointment` row becomes this.
- `reminder` - unchanged. User-set, alarm-bearing, completable.
- `task` - new. Completable, may carry a due date without an alarm. What Canvas will populate.

**`done`/`doneAt` must be CLEARED on every row becoming an event, not merely hidden.** A row that
cannot be done must not carry a stale true - and there is at least one on the real device: a
`COSC 3334` row was ticked during device testing on 2026-09-01. A hidden-but-present flag is
exactly the kind of thing that surfaces later as a lie.

**The day view splits into three sections**, and the split is the whole point:

| Section | Contents | Checkbox |
|---|---|---|
| SCHEDULE | events, time-ordered | none |
| YET TO DO | tasks and reminders not done | yes |
| DONE | tasks and reminders done | yes |

The ratio the day view reports ("4 of 9") counts **tasks only**. Counting classes into a completion
figure is what makes a full teaching day look like a failure.

**Server side:** the Supabase `events` table needs the same reclassification, and **check whether
its `kind` column carries a CHECK constraint** before widening - one bit this project on
2026-08-29, when a constraint written UPPERCASE from a doc comment rejected the lowercase values
the client actually sends.

**Room:** no column is added or dropped, so the identity hash should not change - but the data
UPDATE still needs to be a tracked migration rather than a one-off at startup. Confirm whether that
forces a version bump rather than assuming either way; if it does, `CarDatabase.SCHEMA_VERSION`
moves in lockstep and a migration test comes with it (§5).

## Canvas is its own ticket

Reading submission state from Canvas is a backend job, exactly as Kevin said. It belongs in a
Supabase edge function polling the Canvas API on a schedule and writing task rows, not on the phone:
the phone is the specialized client (ADR 0040), it is not always awake, and an assignment's state
should be true for every client at once.

**Why this is the right shape rather than a convenience:** an auto-tick sourced from Canvas is an
assertion from an external, falsifiable system - which is precisely what CLAUDE.md §4 asks to stand
behind a claim. "You submitted this" because Canvas reports `submitted_at` is honest. The same
sentence inferred from a calendar title is not. The Canvas token is BYO, same shape as the Gemini
key and the Drive grant, so clone-and-run survives.

Open there, not here: which Canvas endpoint (`/api/v1/users/self/todo` vs per-course assignments
with submission included), poll cadence, how a Canvas task reconciles against a Google-imported row
describing the same deadline, and what happens to a task whose assignment is deleted upstream.

## A discussion is more than one deadline (Kevin, 2026-09-05)

*"be careful about the canvas events called discussions. the due date in canvas calendar is one thing
but sometimes it requires a post on wednesday and 2 replies on friday etc."*

Canvas exposes ONE `due_at` per discussion, and it is the LAST obligation (the replies). The initial
post is a separate, earlier deadline that lives only in the assignment description and the syllabus.
The 2026-09-04 reconciliation held back 15 MATH 3391 "first post due Wednesday" rows as probable
duplicates of the Friday discussion; that was wrong, and they are being written as their own task rows
with `structured_meta.parent_canvas_assignment_id` pointing at the Canvas discussion they belong to.

**Binding on the Canvas edge function when it is built:** a discussion's `due_at` may not be treated
as the whole story. Parse the description for an initial-post deadline (or read it from the syllabus
row already present), emit one task per sub-deadline grouped under the parent, and never let a single
`submitted_at` mark all of them done - Canvas reports the discussion submitted on the FIRST post, so
the replies row must key on a later signal (reply count, or stay manual) rather than the parent's
workflow state.
