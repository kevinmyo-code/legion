---
map: home-launcher
title: "HOME becomes a launcher, lists become real lists, and the look goes soft"
charted: 2026-09-27
charted-by: "Kevin + Opus"
effort: "`.scratch/home-launcher/`"
tickets: 5
open: 4
status: open
tags: [map]
---

# HOME becomes a launcher, lists become real lists, and the look goes soft

**Kevin, 2026-09-27, on the Android app:** *"i want a complete redesign of the home page. right now
it needs scrolling. a calendar, then lists then whatever scrolls down. i want a single non scrolling
landing page with buttons i can click to open up and navigate to different pages and reports. every
list should be like a card icon that i can open. the lists now also doesnt look very appealing. it
should look like an actual list."*

## What was true on 2026-09-27

HOME (`ui/CalendarScreen.kt`) was one long scroll: a month grid, the selected day's agenda
(SCHEDULE, RECORDED, one section per routine checklist, YET TO DO, DONE), then `ui/HomeMeterBands.kt`
underneath - Needs you, Body, Money, Fleet, Lists, Recordings, weather, `AreaCard`, News, Ask, the
media mini-bar. That shape is what [[../one-home/map|one-home]] produced on 2026-09-10 from
*"just everything on home page"*. This map is the other half of that sentence arriving: everything
reachable from home, nothing scrolling on it.

The checklist screen (`ui/checklists/ChecklistsScreen.kt`) was a STRUCTURE editor with no checkbox
at all - `REMOVE`, up and down arrows and stamp-text buttons. Ticking only happened on the calendar
day view. That is why a list did not look like a list.

## Every call, settled in one interview (ticket 01)

The interview, a clickable prototype canvas and Kevin's picks are all in [[01-the-shape]]. In short:
a today card over a 2 x 4 tile grid, Groceries is a list and not a tile, lists open to a Keep-style
checklist, the Lists page is a grid of icon cards, and the look moves from mission control to a
softer Material dark - Home, Lists and the shell chrome first, the rest as each screen is next
touched ([[../../docs/adr/0051-design-language-soft-material|ADR 0051]]).

## Tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | The shape: layout, tiles, list style, lists page, look, scope | - |
| 02 | build | Soft theme, bundled font and icons, and the shell chrome restyled | 01 |
| 03 | build | HOME is a launcher; CALENDAR is its own route again; the meter bands retire | 02 |
| 04 | build | Lists as icon cards; a list opens to a real checklist | 02 |
| 05 | task | Ship pass on the A25 | 03, 04 |

03 and 04 touch disjoint files and run in parallel, each in its own worktree.

## What this map does NOT change

- **Tick semantics.** Scheduled lists tick per day, plain lists are done once any live tick exists,
  exactly as `ChecklistController.itemsWithTickState` decides and the web's `lib/checklist.ts`
  mirrors. The list screen gains a checkbox; it does not gain a rule.
- **The calendar page itself.** Month grid and day agenda move to their own route unchanged, still
  in the mission-control look. Restyling it is a later touch.
- **Voice.** No tool added or removed. Every capability HOME reached is still reached (ADR 0035).
- **ADR 0049.** A tick is a tap, not a purchase. Every new string says "ticked".
