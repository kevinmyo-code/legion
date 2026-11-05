---
map: household-finance
ticket: "13"
title: "Home: a Money panel replaces the calendar; the calendar becomes a pop-up"
type: build
status: built
status-detail: "built 2026-11-05 on feat/money-screens, JVM suite green. Owed: Kevin's run on the A25"
blockers: ["07", "08"]
blocked-by: ["[[07-net-worth-and-runway]]", "[[08-budgets-and-pace]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Home: a Money panel replaces the calendar; the calendar becomes a pop-up

**Kevin, 2026-11-05:** *"i cant tell how much of the budget ive used"*, *"the main ui should show. i
think it should replace the calendar. the calendr can be a pop up modal instead"*. He picked
layout A.

## Layout (Kevin, 2026-11-05: replace ONLY the month grid)

*"right now on home i have the calender, below it theres today thu nov 5 with some things i have
going on, then the row of todo list ideas etc. then the app icons etc. i just want that calendar
section replaced the top one"*. Only the month grid at the top of `HomeCalendarArea` is replaced.
Everything below it stays: the "Today" agenda list, the panel chips (To-dos, Lists, Ideas, Games,
Books), the app dock, the categories, the Claude line, Now playing, and the shell's tap-to-talk bar.

```
SAFE TO SPEND  <figure>  until <date>      [Calendar]   <- replaces the month grid
! Top up ~<z> by <date>              (only when needed)
<Month>: <spent> of <cap>                       <pct>
<3 categories closest to their cap, bars with a pace tick>
+ N more on track
------------------------------------------------------
Today, Thu Nov 5   <the existing day list, unchanged>
[To-dos] [Lists] [Ideas] [Games] [Books]
<app dock> <categories> <Claude line> <Now playing>
<tap to talk>  (shell)
```

The panel takes the month grid's height. It shows 3 bars, not all 14; the rest are one tap away in
the Money view (ticket 14). The month grid moves into the `[Calendar]` pop-up.

## What

- **The month grid at the top of `HomeCalendarArea` becomes the Money panel.** The day list under it is untouched.
  - Data comes from `GET /api/finance/safe-to-spend` and `GET /api/finance/budgets` (tickets 07 and 08).
  - When the engine is down, it uses the last cached response, with its as-of time said in words.
- **Each bar is filled to spent/cap, not to the share of the largest category** (today's `MoneyBars`).
  - The pace marker sits at `pace_cents`.
  - Over the cap, the bar turns the warning colour AND says "over by $X". Never colour alone.
  - Pending charges are included and say "includes $X pending".
- **Calendar.** A header `[Calendar]` button opens the existing agenda as a modal bottom sheet. Alfred's `show_agenda_modal` opens the same sheet. The Calendar tile in the grid goes away (the button replaces it).
- **Navigation.** Tapping a bar opens that category's transactions (existing `LedgerCategoryDrilldown`). Tapping safe-to-spend opens the Money screen.
- **Rules.**
  - ADR 0035: Alfred's money answers and this panel read the same endpoints.
  - Soft Material (ADR 0051).
  - The §4 disclosure lines stay: estimates and pending are said in words.

## Verification

- JVM and Compose tests:
  - the bar fill maths
  - the pace marker
  - the over-cap wording
  - the stale-cache wording
  - the agenda modal opens from both the button and the tool
- Roborazzi screenshots of Home updated.
- Kevin's run on the A25.
