---
map: matrix-open
ticket: "04"
title: "Build: Matrix calendar and brief"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: Calendar on Matrix HOME shows today and 7 days; add an event (title, date, time) and see it; edit it; delete it with the second tap; tick a task; Brief shows engine sections with status and Unverified lines, Refresh works, a link opens the browser; with the engine unreachable the Brief says so in words and the saved copy is labelled."
blockers: []
tags: [ticket]
---

# Build: Matrix calendar and brief

Today and the next 7 days as text rows; add / edit / delete an event; the brief as plain text sections with sources in words. Same controllers as the Soft screens.

**Style (Kevin, 2026-10-24): text-only, like Matrix HOME.** Green monospace on black from
`ui/dumb/MatrixScheme.kt`, rows you tap, no cards, icons, images or charts, no rain. A new screen, not
a recolour of the Soft one. Reached from Matrix HOME while the mode is on; the Soft screen is unchanged
when it is off. Every action also says its failure in words.

Verify: ViewModel / UiState tests for the screen's states (loading, empty, unreadable said apart from
empty, error). On the A25: open each from Matrix HOME and do one write.

## What was decided while building

Orchestrator decisions (2026-10-24), as in ticket 03: To-dos, Calendar and Brief on Matrix HOME open
the new text screens (the Brief row no longer opens the Soft sheet while Matrix is on); the LEGION
index lists Calendar and Brief.

- **Calendar scope.** Today plus the next seven days, from `MatrixCalendarViewModel` over a
  `MatrixCalendarSource`. Rows are events (tap opens the form), tasks and reminders (tap ticks and
  unticks through `NotesController.tick`/`tickAppointment`, the Soft funnels). Add, edit and delete an
  event use `EventsAppointmentWriter.addEvent`, `NotesController.updateAppointment` and
  `removeAppointment`: the same calls, no second implementation. A repeating reminder says in words
  it cannot be ticked here. Left to the Soft calendar: month grid, voice notes and checklists on a
  day, editing or deleting a reminder or task, recurring-occurrence handling.
- **No pickers.** Date is typed 2026-10-24 and time 14:30; an empty time is an all-day event
  (UTC midnight, the app's all-day convention). A bad field is refused with a sentence that says
  nothing was saved. Delete asks once more before it deletes.
- **Unreadable vs empty.** A failed read shows "Couldn't read the calendar. This is not a clear
  week" and no day list; "Nothing on this day." appears only after a successful read.
- **Brief.** `MatrixBriefScreen` uses the existing `BriefViewModel`/`BriefUiState`; `MatrixBriefText`
  is the pure mapping. Sections carry their status in words when it is not ok, every unverified line
  gets its own "Unverified" line, links are tappable rows that open the browser, newsletters are
  checked on a tap and dropped on leaving.
