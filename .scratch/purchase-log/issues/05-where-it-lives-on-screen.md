---
map: purchase-log
ticket: "05"
title: "Where the log lives on screen, phone and web"
type: prototype
status: resolved
status-detail: "Kevin picked C, search from Home"
blockers: ["01"]
blocked-by: ["[[01-the-bought-entry]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Where the log lives on screen, phone and web

## Question

2-3 clickable HTML prototypes (Kevin's standing preference): Mia's iPhone family view first, then
the workbench and the phone (384dp).

- A "Bought" list per item or a timeline? Search box.
- "Log it" by hand: item, date (today default), optional store / price / note, private toggle.
- On a list item: "last bought Sep 20 by Mia" (Groceries) vs "last ticked" (other lists).
- Every price shown says it was entered by hand.

## Answer (2026-10-04)

**Kevin picked C: search first, from Home.** Source of record:
`research/05-prototypes/bought-log-prototypes.html`, variant C (plus its workbench and Android
renderings).

- Home gets a "When did we last buy...?" pill; the search screen answers the latest match in a
  sentence naming the exact entry, date and who ("Shampoo, bought Sep 20 by Mia"), then lists the
  other matches; backfilled rows say "Logged by: not recorded".
- "Log it" is its own page: item, date (today), optional store / price ("entered by hand") /
  quantity-note, private toggle (lock, "Only you can see this").
- Groceries items show "last bought Sep 20 · Mia"; other lists keep "last ticked" (ADR 0049).
- Server unreachable: "Can't reach the bought log right now. Nothing was logged." and the typed
  fields are kept.
