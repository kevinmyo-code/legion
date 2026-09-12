---
map: canvas-integration
ticket: "01"
title: "Seven things are due Sunday and the assistant says it knows of none"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Sunday reads empty

**Kevin, 2026-09-11:** *"the AI doesnt see anything thats due on sunday for sch. i asked it and it
didnt know."*

He is right that it is wrong, and the cause is not an empty calendar.

## What is actually due Sunday

From `research/planner-2026-09-01.json`, Canvas's own `due_at_utc`, converted to Houston (CDT,
UTC-5):

| `due_at_utc` | Local | Course | Item |
|---|---|---|---|
| `2026-09-14T04:59:00Z` | **Sun 2026-09-13 23:59** | MATH3391 | Chapter 2 Quiz |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | MKTG3303 | Quiz 3: Ch 8-11 |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | MATH3391 | Module 3: Assignment (WebAssign) |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | COSC4320 | Discussion-2-Incremental Advantage Delivery |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | COSC4320 | Module 2: Assignment 2 - Waterfall Model |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | COSC4320 | Software Engineering Quiz 2 - Ch 2 (submitted) |
| `2026-09-14T04:59:59Z` | **Sun 2026-09-13 23:59** | COSC3334 | Module1: Assignment |

**Not one row in that file carries the date `2026-09-13`.** Every Sunday deadline is stored as
Monday in UTC, because Canvas writes an 11:59pm local deadline as `04:59Z the next day`.

Also worth saying out loud: `2026-09-12T04:59:59Z` is **Friday 2026-09-11 23:59 local** - MATH3391's
"Module 3: Discussion - What Are the Odds?" was due the night Kevin asked, and would read as
Saturday under the same fault.

## Why this is the interesting kind of bug

**Anything that buckets these by the DATE PART of the UTC timestamp puts all seven on Monday, and
Sunday then correctly reports nothing.** The assistant is not failing to look. It is looking at a day
that has been emptied by an off-by-one nobody sees, and CLAUDE.md §1 is explicit that an empty answer
and an unreadable one are different sentences - this is a third thing again, a *wrongly-filled* one,
which reads exactly like the truth.

**This exact fault has already been found once in this codebase, in the other half.**
`service/LiveToolbox.kt`'s `readCalendar` carries the note: *"an all-day row's `startsAt` is UTC
midnight of its date, not a device-zone instant, so a plain window compare can speak the wrong day
aloud (found 2026-09-01, 'the due dates seem to be advanced by 1 day')"*. The fix was
`activeByKindInLocalWindow`, and it is real - **but it fixes the READ, and only for rows that are
genuinely instants.** If the import already flattened `2026-09-14T04:59Z` into an all-day row dated
`2026-09-14`, no read-side zone conversion can recover Sunday: the data itself now says Monday.

**So the first job is to find out which half is broken**, and they need different fixes:

- **Stored as a real instant** (`startsAt = 1789...` = the actual 04:59Z) - then the read path is at
  fault and the window/bucketing is the fix.
- **Stored as an all-day row dated `2026-09-14`** - then the IMPORT is at fault, the read path is
  behaving correctly on bad data, and every affected row has to be re-imported. A read-side patch
  here would be a guess dressed as a fix.

## What could not be checked, and why it is not a conclusion

**The phone dropped its wireless-debugging port mid-investigation**, so the Room table was never
queried. Everything above is from Canvas's own snapshot plus source reading:

- `EventKind`: `APPOINTMENT` was renamed to **`EVENT`** (not to `TASK`) in one-today ticket 08, so
  the imported school rows are `kind = "event"` and `read_calendar`'s `EventKind.EVENT` filter DOES
  cover them. An earlier reading of mine that the filter excluded them was **wrong** - checked and
  discarded rather than acted on.
- `EventKind.TASK` exists and **nothing writes it** (`EventsBackend.kt:42`, and
  `NotesController.kt:929`: *"nothing writes one yet - Canvas is its own ticket"*).

**First step for whoever takes this: get one row.** `adb shell run-as com.kevin.legion` against the
Room DB, or the Django `events` endpoint, and read `startsAt` / `allDay` for "Chapter 2 Quiz MATH
3391". That single row decides which fix this ticket is.

## The structural half: there is no Canvas sync at all

This map has **no tickets and no code** - only `research/planner-2026-09-01.json`. Nothing pulls
Canvas. Which means, independent of the timezone fault:

1. **The data is ten days stale.** Anything assigned since 2026-09-01 does not exist on the phone.
2. **The snapshot is not even complete.** Its own `note` field says: *"truncated at 50KB by
   get_page_text; 67 of an unknown larger total"*. Nobody knows what is missing.
3. **`submitted` is captured and unused.** The snapshot carries it per item (one Sunday quiz is
   already `submitted: true`), which is exactly what one-today ticket 08 asked for - *"assignments
   tasks > should read from canvas if done or not and auto tick"* - and nothing consumes it.

ADR 0044 says this belongs on the server: Django polls Canvas while the phone is asleep, which is
django-engine's stated reason to exist. That is a separate ticket and should be opened when this one
is understood, not before - fixing the day bug on ten-day-old data still leaves him asking about a
week Canvas knows about and LEGION does not.

## Verification

- The decisive check is on the phone or the server, not in a unit test: ask for Sunday and get seven
  items. **A green suite will not settle this** - whatever fixture a test uses will encode whichever
  reading of the data the fix assumed.
- A test that pins a 23:59-local deadline stored as next-day-UTC and asserts it reports on the LOCAL
  day. That is the regression guard, and it is the one that was missing when the same fault was found
  on 2026-09-01.
- Check the Friday case too (`2026-09-12T04:59:59Z` -> Fri 23:59 local). A fix that only special-cases
  Sunday is not a fix.
