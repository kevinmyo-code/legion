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

---

## A DEFECT FOUND WHILE GROUNDING THIS TICKET (2026-08-26), and it blocks the rewire

`EventsReconcile.kt:187` calls `deleteAllForReplicaRefresh()` immediately before the `upsert` loop
at `:190`. So `EventReplicaDao.upsert`'s `getByServerId` always returns null, every row takes the
`insert(row.copy(id = 0))` branch, and **every reconcile re-mints every local id.**

The hand-written id-preserving upsert at `EventReplica.kt:157` is defeated by its only production
caller. Its doc comment describes a guarantee the code does not deliver.

**Why no test caught it:** `EventsReconcileTest.kt:247-248` asserts `replicaCountAfter`, never ids.
That is ticket 09's blind spot one layer out - a count-based check cannot see that the thing it was
protecting got replaced rather than preserved. Same shape as CLAUDE.md section 4 rule 6.

## The id hazard is wider than this ticket first said

`ListItem.id` is not a `list_items` id at all - it is `EngineRecord.id`, from the shared `records`
id space (`NotesController.toListItem`, `:99`). Four persisted things key on it:

| Keyed on that id | This ticket originally said |
|---|---|
| `list_item_skips.itemId` | not mentioned |
| `workout_set_logs.sourceListItemId` | named, correct |
| `muted_reminders.recordId` | **not mentioned - a muted reminder starts alarming again** |
| AlarmManager request codes + notification ids | named; OS-held, cannot be migrated, only re-armed |
| `GoalChecklistSync` | named, but it is IN-MEMORY, not persisted - the ticket was half wrong |

## RULED 2026-08-26 (Kevin): carry the engine id into the replica

`events_replica.id` is SET from the engine record's own `records.id`, looked up by `origin_guid`,
rather than allocated by autoincrement. Rows with no engine ancestor (created post-cutover from the
laptop surface) autoincrement from a high watermark.

**The id space therefore never changes at the flip.** No rekey of the three tables, no alarm
re-arm, no flip-day window - skips, mutes, workout logs and every already-armed `PendingIntent`
keep working untouched. It also fixes the re-mint defect above by construction: an id that is
derived cannot drift, so there is nothing for a later reconcile to re-allocate.

Rejected: fix-the-upsert-then-rekey-once (real flip-day window, and the alarm re-arm is the part
with no test anywhere - `AlarmScheduler` has zero tests); and re-keying alarms off the guid
(kills the bug class permanently but is a far larger change across `AlarmScheduler`, both
receivers and the deep link, with no test scaffolding to land it against).

**Verification this ruling adds:** a test asserting id STABILITY across two reconciles, not counts.
The absence of exactly that test is what let the defect live.

## RULED 2026-08-26 (Kevin): the agenda renders an undated item as tomorrow, and it lands here

Ticket 07 ruling 2's inferred-tomorrow is a READ-SIDE rendering rule and **nothing implements it**.
`DatesAgenda`'s two queries hard-filter `dueAt IS NOT NULL` (`EngineRecordDao.kt:70-84`) and
`toAgendaItems` drops null rows (`DatesAgenda.kt:83`), so 53 of Kevin's 56 Notes items never reach
the agenda at all. That is a bug against ruling 2, not a missing feature, and it is fixed in this
ticket while the surrounding code is open.

**The date is applied at RENDER only and never written back.** Storage stays NULL. Writing an
inferred date would assert something the user never said - CLAUDE.md section 4 rule 5, and the
explicit "what it does not license" clause of ticket 07's own ruling.

Kevin's recollection when asked was that he had ruled every item would be dated; the record says
option 1, `starts_at` nullable, applied. Both are reconcilable: every item DISPLAYS a date,
storage keeps NULL. Recorded here because the difference is the whole ruling.
