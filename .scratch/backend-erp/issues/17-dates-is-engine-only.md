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
