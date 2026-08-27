---
type: build
status: built
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

## BUILT 2026-08-26 - three commits, suite green, one thing owed on the phone

- `b17bc88` derive the replica's local id instead of reminting it every reconcile
- `e91a296` the dual-path rewire itself, plus the `createdAt` defect found under it
- `c79da08` the agenda shows an undated item as tomorrow, and says so in words

Suite 2,676, 0 failures. `compileDebugKotlin` and `compileDebugAndroidTestKotlin` green, both
`-Pnokey`. Room v40 -> v41 (additive, verbatim generated SQL, schema JSON committed, migration
test written but NOT RUN - same posture as `CarDatabaseMigration39To40Test`).

**A second defect was found under the first, and it is the one that would have shipped.**
`SupabaseEventsBackend.uploadMigratedEvent` inserted without `created_at`, taking Postgres's own
`default now()`. At cutover every migrated row's creation time would have become the migration
moment: `GoalChecklistSync`'s "already materialized today" gate (`:109`) would read all 56 notes as
created today, and `LogDigestBuilder`'s FRESH/AGING/STALE buckets would all reset. Found by tracing
why the replica mapper had no source for `ListItem.createdAt`, not by a failing test. No test would
have caught it, because nothing asserted the value survived the round trip. One does now.

**The inferred instant is `now + 24h`, not a calendar midnight.** A judgment call, recorded because
the ruling does not dictate it: no timezone needed, always ahead of now, lands on calendar tomorrow
in every case except a near-midnight DST spring-forward. The rendered text never shows the date, so
the only thing affected is sort position.

## OWED ON THE PHONE, not claimed as done

**A reminder set before the cutover still fires after it.** This ticket's own verification bar, and
a green unit suite cannot settle it. `AlarmScheduler` has ZERO tests repo-wide and the
`PendingIntent` request-code contract is exercised by nothing at all. Kevin's carry-the-engine-id
ruling is what makes it likely to hold - the id space never changes, so an armed alarm keeps
resolving - but likely is not verified.

Also owed: `CarDatabaseMigration40To41Test` has not been RUN (it needs a device), and the
configured Notes path has never touched a real Supabase project.

## INCIDENT, 2026-08-26: the sweep marked 51 deleted todos "missed"

The "never touched a real Supabase project" line above stopped being true the moment Kevin's phone
picked up `e91a296`. First launch of that build ran `AlarmScheduler.rescheduleAll`'s normal
start-up sweep, which walked `events_replica` and called `NotesController.markMissed` on every row
whose `startsAt` had passed. His home screen went from "1 missed" to "51 missed" in the same
second.

**Evidence, traced off a read-only pull of the live database:**
- The 51 rows carry `missedAt` stamped 08:37:19-08:37:21 on 2026-08-26, sequential, one per row -
  the exact moment that build first launched. Nothing else touches `missedAt` at that cadence.
- The engine has `missedAt` on exactly one Notes `Item`; the Dates `Event` record type has no
  `missedAt` field at all. So all 51 values were minted at sweep time, not carried from the engine.
- All 106 `source='legion'` replica rows carry `sortOrder`, which only `EventsReconcile`'s Notes
  branch ever sets - so all 106 are Notes Items, not Dates Events. **The engine holds only 56
  active Items.** The other 50 are todos Kevin deleted on the phone: the engine soft-deletes them,
  `EventsReconcile` never propagates that deletion to the server, and the replica keeps serving
  them back as live rows on every refetch.

So the sweep read 50 historical, already-deleted todos plus 1 genuinely overdue one, and told
Kevin he had missed all 51, today, in a live write to his own Supabase project.

**Fixed in this pass:** `AlarmScheduler.rescheduleAll` no longer calls `markMissed` at all on the
configured (replica-backed) path - see that function's own doc comment and
`AlarmScheduler.shouldSweepMarkMissed`. Under-claiming (a real overdue reminder loses its missed
badge on a configured install until this is ruled properly) on purpose: a withheld badge is a much
smaller harm than 51 invented ones written to a server. **Kevin's live rows were left untouched** -
clearing them is his call, and needed this fix in place first regardless.

**NOT fixed here, and UNRULED:** `EventsReconcile` never propagates an engine-side deletion to the
server at all, in either direction - a deleted todo simply stays live in `events_replica` forever,
resurrected by the next refill. This is the actual root cause; the sweep guard above only stops
this one symptom (retroactive missed-marking) from writing to the server. Fixing the propagation
itself needs a ruling on whether the phone is allowed to delete rows on the server, which is not a
call to make inside a bugfix - ticket 04's mirror-reimport hole is the standing cautionary
precedent for a client deleting or resurrecting server state without that ruling in hand first.

## RULED 2026-08-27, both open questions. Delegated to me; open to reversal.

### 1. The discriminator: `events.kind`, set at upload from the originating record type

The merged table needs to say what a row IS, because `NotesController` owns reminders and must not
own appointments. `source='legion'` cannot do it - all 106 legion-source rows are Notes Items, and a
legion-AUTHORED calendar event would be indistinguishable from a todo.

**Add `kind text not null default 'reminder' check (kind in ('reminder','appointment'))`.** The
reconcile sets it from the record type it read: a Notes `Item` is a `reminder`, a Dates `Event` is an
`appointment`. The Google importer writes `appointment`.

**Why a stored column rather than a derived rule:** the phone is what knows which record type a row
came from, and after ruling 7 retires the engine that knowledge exists nowhere else. Deriving it
later from shape - "has a sortOrder, therefore a todo" - is the kind of inference that works until
the day it does not, and this is the column that decides whether an alarm fires.

`NotesController`'s list and alarm reads filter `kind = 'reminder'`. That is what lets the start-up
sweep guard from `6eb2c2b` come back off: the sweep was blocked because the read was wrong, and once
the read is right the sweep is safe again. **Remove the guard in the same commit that lands the
filter, not before and not after** - a guard left in place over a fixed read is dead code that
someone eventually deletes without knowing what it was for.

Default `'reminder'` is deliberate: a row whose origin is unknown is treated as something the app
owns, which is the conservative direction. An appointment wrongly treated as a reminder is visible
and annoying; a reminder wrongly treated as an appointment silently never fires.

### 2. Deletion propagation: the reconcile soft-deletes what it uploaded and no longer has

**Bounded by `origin_guid`, and that bound is the whole safety argument.** After the upload pass,
any server row that HAS an `origin_guid` whose engine record is now trashed or absent gets
`deleted_at` set. A row with a NULL `origin_guid` is never touched by this - it was created
somewhere else (the laptop, a future surface) and this phone has no standing to delete it.

Ticket 04's caution was a spreadsheet minting records past the gate - unbounded authority flowing
INTO the system from an unverifiable source. This is the opposite and it is bounded: the phone can
only retract rows it can prove it created, identified by the very column that records it created
them. Soft delete, never hard, so the retraction is itself auditable.

Without this, the 50 todos Kevin deleted stay live on the server forever and come back through every
replica refill - which is the actual root cause of the 51 false "missed", not the sweep that reported
them.
