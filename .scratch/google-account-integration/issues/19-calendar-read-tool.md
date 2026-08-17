# BUILD: Alfred cannot read the calendar at all

Type: task
Status: resolved (2026-08-16, verified built - premise was false)
Blocked by: -

## Question

Nothing to decide. Found 2026-08-13 when Kevin reported the assistant "can't access calendar".

**The hole, and it is a planning hole rather than a build defect.** LEGION can now *write* an
appointment by voice (`manage_item` with `kind=appointment`, ticket 14) and *render* Google events
on Today and in the Notes stream (ticket 13). **Nothing lets the assistant READ the calendar.**
There is no tool. Asked "what's on my calendar this week", Alfred has no way to answer and correctly
says he cannot.

Nobody removed it - it was never specified. [Ticket 08](08-deck-surface.md) decided calendar is a
surface and Gmail is voice-only, and that framing quietly carried an assumption that calendar did
not need voice access. [Ticket 05](05-what-counts-as-worth-reading.md) wrote the mail tools and
never had a calendar counterpart. The map's own **conflict-awareness** item in "Not yet specified"
("Alfred should know when Kevin is busy") depends entirely on this and was never ticketed.

**Build a calendar read tool.**

1. One tool, e.g. `read_calendar(from, to)` or a window in plain terms ("today", "this week"),
   returning title, start, end and all-day flag for events in the window.
2. **Reuse `CalendarProvider.eventsInWindow`.** Do not write a second query.
   **Apply [ticket 17](17-read-all-calendars.md)'s split if it has landed** - reading must cover
   every `com.google` calendar, not only writable ones, or Kevin's holidays stay invisible to voice
   exactly as they are on screen. If 17 has not landed, this ticket depends on it.
3. The permission is `READ_CALENDAR`, already in the manifest. A service has no Activity to raise a
   dialog from, so when it is missing the tool must **say so in words** and point at the screen that
   can grant it - never return an empty window, which would read as "you have nothing on". Same
   rule as ticket 08 point 5 and the same failure `memory/MEMORY.md`'s L15 note is about.
4. **Tool budget**: this is +1 (71 -> 72). Justified: the write half already exists and a domain the
   assistant can write to but not read from is worse than either alone.
5. **Conflict awareness stays out of scope here.** This ticket makes the calendar readable; whether
   Alfred volunteers "you're busy then" is the map's un-ticketed fog item and needs its own decision.

## Verification

On the device, by voice: "what's on my calendar this week" returns the real events, and the answer
includes something from a read-only calendar (e.g. a US holiday) once ticket 17 has landed.

## Answer

**VERIFIED BUILT 2026-08-16 - the ticket's own premise was false.** Alfred can read the calendar.
Closed on evidence. All `traced`.

- **`read_calendar` is declared and WIRED**: `LiveToolbox.kt:1241-1258`, inside `declarations()`,
  dispatched at `:1539` to `readCalendar` (`:1834`). Returns `title`, `start`, `end`, `all_day`
  (`:1846-1860`) over a required `from`/`to` window.
- **It reuses `eventsInWindow`** (`:1843`) rather than issuing a second `CalendarContract` query, so
  it inherits [ticket 17](17-read-all-calendars.md)'s all-calendars read - the description's promise
  about subscribed and read-only calendars is therefore true rather than aspirational.
- **A missing `READ_CALENDAR` grant refuses in words and names the screen**, checked BEFORE parsing
  (`:1835-1837`): "I don't have permission to read your calendar yet. Grant calendar access from the
  Today screen to let me see it." (`CalendarReadToolLogic.kt:23-25`). It returns `success=false`
  with **no** `events` array, so an ungranted read can never be mistaken for an empty calendar -
  pinned by `CalendarReadToolLogicTest.kt:67, 76`. A separate message covers a malformed window.

This is the fourth time today a ticket described work the tree already had. See
`library/lessons.md` L24.
