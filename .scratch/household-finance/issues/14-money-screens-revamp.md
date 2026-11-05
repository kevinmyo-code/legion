---
map: household-finance
ticket: "14"
title: "Money screens: swipe between months, read-only account list, budget bars against their own cap"
type: build
status: built
status-detail: "built 2026-11-05 on feat/money-screens, JVM suite green. Owed: Kevin's run; statements read from balance days until ticket 09"
blockers: ["13"]
blocked-by: ["[[13-home-money-panel]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Money screens revamp

**Kevin, 2026-11-05:**
- *"tapping more > money shows current month but i want to be able to swipe to different months too."*
- *"tapping money details works but the bank accounts no need to be selectable. its getting wrapped and messing with the screen."*

## What

- **Swipe between months** on More > Money (`ui/LedgerScreen.kt`).
  - A `HorizontalPager` over `pnlMonthsWithData`, newest on the right, opening on the current month. Paging changes `pnlMonth`, so the existing reload effect does the rest.
  - The month header keeps its arrows and gains "swipe for other months" the first time.
  - The pager never pages past a month that cannot have data (the existing bound).
- **The accounts list is read-only.**
  - Remove the account selection: the `accountFilter` chips and `NominatedAccountSection` with its "Nominated account" choice.
  - Each account is one row: name (ellipsised, never wrapped), last 4, balance, and as-of in words.
  - The Home CRED tile's nominated-account balance retires with ticket 13 (safe to spend replaces it). Keep the stored preference so nothing breaks, but stop reading it. Note this in `LedgerNominatedAccountPreferences`.
- **Budget section.**
  - Uses the same bar component as the Home panel (ticket 13): filled to spent/cap, with a pace marker, "over by $X" in words, and pending labelled.
  - Tap a bar for the category drilldown.
  - The existing set-budget dialog stays.
- **Rules.**
  - ADR 0035: same controllers.
  - The §4 disclosure lines stay.
  - Soft Material.

## Verification

- Compose tests:
  - pager bounds
  - a page change reloads that month
  - account rows are not clickable, and their text is a single line
  - budget bar fill and pace
- Roborazzi screenshots updated.
- Kevin's run.
