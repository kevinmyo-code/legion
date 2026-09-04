---
map: one-today
ticket: "09"
title: "A list you tick every day, and can look back on"
type: build
status: open
status-detail: "Data layer in flight. UI is a second slice."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# A list you tick every day, and can look back on

**Kevin, 2026-09-04:** *"bio, maintenance etc should all just be todos. so its like, i create the
list, name it something (bio) then under the list > 3 sets goblet squats etc etc. and i can make the
list be a daily reoccuring thing or not, then i tick off if i do it, end of day it records and
resets. i can look back and see what i did etc."*

One user-authored mechanism replacing several bespoke ones: a named list, optionally daily, ticked
per day, with the day's result kept and browsable.

## What already exists, and why it is not this

`advisor/GoalChecklistSync.kt` is the same PATTERN and the wrong SHAPE. It materialises today's
lines fresh each day as ordinary one-off items, you tick them, tomorrow regenerates - no reset job,
triggered on app open. That much is exactly right and is where the design below comes from.

It is not reusable as-is on three counts, all of them structural rather than cosmetic:

1. **The lines are derived, not authored.** They come from `mealTargetDao` / `sleepTargetDao` /
   `workoutPlanItemDao`. Kevin wants to type "3 sets goblet squats" himself.
2. **It is welded to one hardcoded list** via `ITEM_PREFIX = "Plan: "`, which is how it tells its own
   lines apart from everything else sharing that list.
3. **Its history rolls off.** `RETENTION_DAYS = 14`, and the look-back surface is a 7-day window of
   `doneAt` values, deliberately *"shown, never scored"*. Correct for a plan-adherence nudge, useless
   for *"look back and see what i did"*.

## Decided 2026-09-04

**1. There is no reset and no nightly job.** "End of day it records and resets" is what Kevin SEES,
not what gets built. Every daily mechanism in this app is app-open-triggered or an AlarmManager shot
at a specific instant; there is no WorkManager dependency and `sync/ScheduledBackup.kt` records that
as a deliberate ruling. A midnight sweep is the part that rots: phone off at midnight, a timezone
change, the app unopened for three days, the job running twice and double-recording.

**2. A tick is a row keyed by `(item, day)`.** The reset falls out for free - tomorrow queries
tomorrow and finds nothing. History is the same query against a past day. A missed day reads as
missed because no row was written, not because a job decided so. Nothing to run twice, nothing to
miss.

**3. `day` and `tickedAt` are different facts and both are stored.** `day` is the day the tick counts
for; `tickedAt` is when the user actually tapped. They diverge the moment someone ticks yesterday
this morning, and collapsing them makes a retroactive tick indistinguishable from a real-time one.

**4. One tick model, not two.** A non-recurring list uses the same `(item, day)` ticks; "done" for it
means a tick exists on any day. No `done` boolean on the item. A non-recurring list therefore still
records WHEN each line happened, which the boolean would have thrown away.

**5. Maintenance does NOT join this mechanism.** Kevin named it, and it does not fit. An oil change is
due on miles-or-months since the last one, and "done" writes a `ServiceRecord` that moves the anchor
forward - see [[05-maintenance-has-no-date-axis]], which traces the anchor being derived at read time
from `service_records` and the Supabase schema deliberately carrying no last-done columns at all.
There is no day an oil change belongs to. It may BORROW the checklist UI for "what is due now"; it
does not share the table, and forcing it in would replace a working dual-axis due calculation with a
worse one.

## The two traps that make history lie

Both are the same failure - the past being described by today's state - and both must be closed in
the CONTROLLER, so no future screen can get it wrong.

**A day before the list existed shows NOTHING**, not a wall of things you failed to do. Create "bio"
today, look back at last Tuesday, and it is blank: nothing was missed, the list did not exist. Gate
on the checklist's `createdAt` compared as a LOCAL DAY, never as a raw millisecond timestamp.

**Editing or deleting an item must not rewrite the past.** Drop "goblet squats" next month and last
week still shows you did them. Items soft-delete, matching the tombstone posture used everywhere
else in this schema; ticks are never cascaded away, and a history read resolves item text even for a
soft-deleted item.

## Build

Three tables, `checklists` / `checklist_items` / `checklist_ticks`, carrying the sync columns from
the start so syncing later needs no second migration. Unique index on `(itemId, day)` - ticking twice
is one tick. `checklists/ChecklistController.kt` as the single write funnel, following
`notes/NotesController.kt`'s shape.

Room v63 -> v64 under §5: verbatim generated SQL (**not** an ALTER sequence - one written that way
crashed the phone twice in v60->v61 and the cause was never established), indices declared on the
entity, `64.json` committed, `CarDatabase.SCHEMA_VERSION` in lockstep, migration test diffed against
the LIVE generated JSON rather than the migration's own output.

**Sync is a later slice** and is not in scope here. Unlike voice notes (which Kevin ruled sync-but-
not-Realtime, device copy primary), these are small rows edited from anywhere and should eventually
go fully live on the standard four-part template.

## UI, second slice

Each checklist that applies to a selected day gets its own named section in the day view, rather than
its lines being poured into YET TO DO - a six-line bio routine would swamp a day's actual todos, and
the list's name is the thing Kevin picked. `ui/goals/GoalChecklistPanel.kt` already occupies exactly
this position in the day view for the bio plan and is the shape to follow.

Open: whether a checklist section renders for a past day read-only or stays tickable (a retroactive
tick is already representable - see decision 3 - so this is a UI call, not a data one).
