---
map: household-finance
ticket: "13"
title: "Home: a Money panel replaces the calendar; the calendar becomes a pop-up"
type: build
status: open
status-detail: "charted 2026-11-05, Kevin picked layout A"
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

## Layout A (Kevin's pick)

```
Thu Nov 5                     [Calendar] [Alfred]
SAFE TO SPEND                       until <date>
  <figure>
  checking <x> - card owed <y>
  ! Top up ~<z> by <date>          (only when needed)

<MONTH> BUDGET          <spent> of <cap>   <pct>
Groceries   [####------|---]   spent / cap
...one bar per budgeted category...
            | = where you should be today (day d of n)

[Lists] [Body] [News] [More]
```

## What

- **The Home main panel (today `HomeCalendar`) shows the Money panel.**
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
