---
map: home-launcher
ticket: "05"
title: "Ship pass: the launcher and the lists on the A25"
type: task
status: open
status-detail: >
  Opened 2026-09-27. Runs once 03 and 04 are merged on feat/home-redesign.
blockers: ["03", "04"]
blocked-by: ["[[03-home-is-a-launcher]]", "[[04-lists-as-icon-cards]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Ship pass: the launcher and the lists on the A25

Install the merged `feat/home-redesign` build on the A25 and check by finger what a green suite
cannot. Screenshots land in `.scratch/home-launcher/research/device-shots/` and **survive the run -
no cleanup step removes them.**

1. Cold start lands on HOME. Nothing scrolls; nothing is clipped; the today card and all eight
   tiles are fully visible. Screenshot.
2. Each tile opens the right screen and back returns to HOME: Calendar, Lists, Money, Body, Fleet,
   Recordings, News, Reports.
3. Money has the new "Groceries and receipts" row and it opens the pantry screen.
4. Record from the Recordings tile: start, the tile says "Recording", stop, the count goes up.
5. The today card: the date is today's, the weather or its unavailable sentence shows, the due
   chip matches the calendar's day view for today.
6. Status line: sync and OBD in words; the settings button opens Settings; if an alarm exists, the
   pill opens the Calendar.
7. Talk bar: tap starts a turn; with the mic permission revoked, the pill says so.
8. Lists: the page shows routines and lists as icon cards; open Groceries; tick an item (it sinks
   to "ticked"); untick it (it comes back); add an item with the keyboard's Done; long-press, move
   it up; collapse and expand the ticked group.
9. A routine: tick an item, the card reads "N/M today"; a measured item with an empty field refuses
   in words under the row.
10. Tick every item on a plain test list: the delete offer appears; delete confirms and the list
    goes.
11. A reminder notification tap (post one due a minute out) opens the Calendar with its editor.
12. Airplane mode: tick an item; it shows "Not synced yet"; back online it clears.

Any step that cannot run is reported as skipped with its reason - never silently. Every claim tagged
`on-device` only if a screenshot or a logcat line backs it.
