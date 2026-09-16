---
map: web-calendar-and-lists
ticket: "01"
title: "The calendar widget on the web home - month grid plus day view"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The calendar widget

**Kevin, 2026-09-16:** *"i want the calendar widget, much like my android landing page"*

## What exists to copy from

`ui/CalendarScreen.kt` is the reference and the 2026-09-01 ruling behind it is *"month grid
primary"* - the grid sits above a day view for the selected day. one-home ticket 01's resolution
restated the order when CALENDAR became HOME. **The web home should read as the same surface, not
as a different product.**

The data is already there: `GET /api/changes?aspects=events` returns every `Event` row, and
`useChanges` already pulls it for the horizon strip. No new endpoint.

## What to build

A month grid on `_authed.index.tsx`, above the day view, with a day view for whichever day is
selected (today by default).

1. **Each day cell shows density, not content.** The Android grid marks a day that has something;
   it does not try to render titles in a 40px box. Same here - a count or a dot, and the titles
   belong to the day view below.
2. **`kind` decides the mark.** An `event` passes; a `task` is due and carries `done`. The existing
   `HorizonStrip` already draws this distinction (count = unfinished tasks, events get a dot) -
   **reuse that vocabulary rather than inventing a second one three inches away.**
3. **A day with nothing renders as a day with nothing**, not as an error and not as a gap. §1's
   empty-versus-unreadable rule: if `useChanges` failed, the grid says it could not read the
   calendar. It never draws an empty month that means "could not ask".
4. **Month navigation** - previous/next, and a control that returns to today.

## The all-day trap, and it is not theoretical

`Event.activeByKindInLocalWindow`'s doc comment records it: an all-day row's `startsAt` is UTC
midnight of its date, NOT a device-zone instant, so bucketing by a naive local-day conversion
moves it to the adjacent day. This bit the Android app on 2026-09-01 (*"the due dates seem to be
advanced by 1 day"*) and `lib/horizon.ts` on the web already had to solve it once.

**Read `lib/horizon.ts` first and reuse its day bucketing.** Do not write a second date-bucketing
function in a component; if the logic needs to grow, it grows in `lib/` where it is testable.

A Canvas deadline is written as `04:59Z the next day` (11:59pm local in Houston) and is `allDay =
0` with a true instant - those bucket correctly by ordinary conversion. It is the `allDay = 1`
rows that need the UTC-recovery idiom.

## Verification

- `npm run build` and `npx tsc --noEmit` clean in `server/frontend/`.
- Vitest over the day-bucketing: an all-day row on the 1st lands on the 1st at UTC-5, and a
  23:59-local task lands on its own day, not the next.
- The month containing 2026-09-16 shows MATH 3391's Module 4 discussion on Wednesday the 16th -
  that row is live on the device and is the one Kevin asked about.
- At 400px wide the grid still fits, gutters intact, no horizontal body scroll.
