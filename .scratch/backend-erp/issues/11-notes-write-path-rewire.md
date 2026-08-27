---
type: build
status: open
blocked_by: []
map: backend-erp
---

# Notes write path: rewire the controller

**The build ticket ticket 07 leaves behind.** The blocking decision is made (`starts_at` is
nullable, applied) and the data layer is built and proven: 325 records uploaded and verified in
Postgres, Room v40 replica migrated on real data. **What does not exist is the write path.** Nothing
keeps the server fresh after a reconcile - the app still writes only to the engine.

## Why this was deferred rather than done

`notes/NotesController.kt` is 503 lines and is the single live write surface for todos, reminders,
recurrence, place triggers and alarms. Two hazards make it a real refactor rather than a port:

1. **`ListItem.id` is an `AlarmManager` `PendingIntent` request code**, and is a foreign key from
   `WorkoutSetLog`/`GoalChecklistSync`. An id that changes on a replica refresh silently breaks real
   alarms on the phone. `EventReplica` already solves this shape with a hand-written
   read-then-update-preserving-id upsert instead of `OnConflictStrategy.REPLACE` - read its doc
   comment before writing anything.
2. The per-item "which store owns this row" question was undecidable while undated items had no
   server shape. **That is now resolved** - both dated and undated items have one home - which is
   precisely what unblocks this ticket.

## Scope

Same dual path as places, pantry and events: unconfigured behaves exactly as today; configured makes
the server the system of record, reads cache-first from the replica, and writes only on ACK.

`DatesAgenda`, `ReminderController` and `AlarmScheduler` all read through this controller and must
keep working. The agenda's ordering must keep the NULLS-LAST policy that v40 introduced -
`ORDER BY (startsAt IS NULL), startsAt ASC`, since this app's minimum SQLite predates the keyword.

## Verification bar

Beyond the usual: **a reminder set before the cutover still fires after it.** That is the one thing
a green unit suite will not tell you, and it is the failure that would matter most.
