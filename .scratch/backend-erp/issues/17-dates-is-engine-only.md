---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The Dates aspect has no configured/unconfigured split to retire

**Found 2026-08-27 building ticket 15 step 4 (notes). Reported rather than improvised, because
nothing in ticket 15's ruling covers it.**

Steps 1, 2 and 4 each moved an aspect's UNCONFIGURED path off the engine, because those aspects
branched configured-to-a-replica and unconfigured-to-the-engine. **Dates never branched.**
`engine/dates/DatesAgenda.kt`, `service/DatesAlarmScheduler.kt`, `calendar/OpenerCalendarBriefing.kt`
and `calendar/CalendarImportController.kt` all read and write the engine unconditionally, on every
install.

That is the same shape ticket 16 records for fleet's `ServiceHistory`/`MaintenanceSchedule`, and it
has the same consequence: **step 6 cannot delete `engine/` until this is answered.**

## The specific hazard, and it is worse than a stale read

Step 4's copier does move Dates `Event` records into the local `events` table, tagged
`kind = 'appointment'`. That copy is additive and harmless. **But it is a one-time snapshot that
starts drifting immediately**, because `CalendarImportController` keeps writing new Dates events
straight to the engine every time the app foregrounds.

So the events table holds appointments as of the copy, the engine holds the truth, and nothing on
the unconfigured path reads `kind = 'appointment'` back out yet. Nobody is harmed today - no reader
depends on those rows - but the longer it sits, the more the two diverge, and a future reader
pointed at the wrong one inherits a silent gap rather than an obvious one.

## What needs deciding

1. **Does Dates repoint at all, or does it follow fleet's ticket 14 shape** (engine-primary locally,
   Postgres as a projection)? Dates is unlike fleet in one important way: ruling 4 merged todos INTO
   events, so the Notes half of this same table is already repointed. Leaving Dates on the engine
   means one table is written by two different stores depending on which record type you are, which
   is the shape ticket 15's own notes ruling rejected.
2. **`CalendarImportController` is the live writer** and ticket 01 ruling 5 removes Google Calendar
   entirely. The order matters: if Google is cut first, this writer disappears and the question
   shrinks to a one-time copy. If Dates repoints first, the importer has to move with it.

**Recommendation: settle ruling 5's Google removal first**, then Dates is a one-time copy with no
live writer to chase. That also matches the C3 constraint already in the map - the importer must be
widened and run unbounded BEFORE Google is cut, and that widening is already built (`6e36ef1`).

## RULED 2026-08-28, and this REVISES my own earlier recommendation in this same ticket

Delegated to me ("resolve everything with ur recommendations"); open to reversal.

**Earlier in this ticket I recommended settling Google's removal first, so Dates would become a
one-time copy with no live writer to chase. That was wrong, and worth saying plainly rather than
quietly replacing.** It made Dates wait on a removal that itself waits on an unbounded importer run
that needs the phone - a chain of three, to avoid a problem that does not need avoiding.

**Ruled instead: Dates repoints onto the SAME `events` table Notes already uses.**

The reason is one the earlier recommendation missed. **Ruling 4 merged todos INTO events** - they are
not two aspects sharing a name, they are one table by design, and step 4 already renamed
`events_replica` to `events` and put Notes on it for both paths. `events.kind` exists precisely to
tell a `reminder` from an `appointment` in that shared table.

So Dates does not need a table of its own, a copy that drifts, or a removal to happen first. It
needs to write `kind = 'appointment'` rows to the table its other half already lives in.

That also dissolves the drift this ticket was filed about: `CalendarImportController` writing new
Dates events to the engine on every foreground was only a problem because the copy landed somewhere
the writer did not. Point the writer at the same place and there is nothing to drift from.

**And it removes the tension ticket 15's own notes ruling named:** leaving Dates on the engine would
mean one table written by two different stores depending on which record type a row is, which is the
shape that ruling rejected.

**Google's removal is now independent of this**, which is the real gain. C3 still binds - widen the
importer, run it unbounded, verify, THEN cut - and the widening is built (`6e36ef1`). But that
sequence is about not losing the class metadata living in Google's descriptions, and it has nothing
to do with which local table Dates writes to.
