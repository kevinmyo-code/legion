---
map: recruiter-demo
ticket: "14"
title: "Web Home becomes a household dashboard: the Morning briefing"
type: build
status: open
status-detail: "after ticket 13 lands (same shell and routes)"
blockers: ["13"]
blocked-by: ["[[13-per-visitor-demo-household]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Web Home becomes a household dashboard: the Morning briefing

## Why (Kevin, 2026-11-02)

*"lets also revamp the real app. the web page that i open in chrome today. today its like an admin
panel, i want it to be a proper dashbord for my household"*. Three prototypes in
`C:\Users\kevin\Documents\legion-dashboard-prototypes\` (private, outside the repo); Kevin chose
**A, Morning briefing** (`a-morning-briefing.html`; sources in `_src\`).

## What

Rebuild `/` (`routes/_authed.index.tsx`) to direction A, on real data, for members and recruiter visits:

- Alfred bar at the top (the same assistant component; opens the existing chat).
- Greeting + one plain sentence about the day, built from facts only (CLAUDE.md §1: a sentence that
  states its facts or forbids its subject; no invented events).
- 1 Next up: next event as a big card, rest of today beside it.
- 2 Worth a look: due car service, bills/reminders due, unverified card charges needing a category,
  paused bank link (members only). Each item states its source and is actionable.
- 3 Money this month: spent vs budget, running total vs budget line, "show as a table", balances,
  every figure labelled Verified / Unverified / Estimate in words.
- 4 Lists: tickable checklist items.
- 5 Around the house: groceries with macro ESTIMATES, voice notes, places, books, body (members only).
- Closing line ("That is everything for this morning").
- Slim icon rail with labels (from A). Dark mode is the app's selected dark palette.
- Recruiter visit: demo banner, no body, no bank link (ticket 13 rulings).

## Rules

- No compulsion mechanics: no streaks, no "you haven't...", no engagement nags (§7 compulsion test).
- Estimates labelled; unreadable vs empty are different sentences.
- Charts: one axis, thin marks, recessive grid, legends for 2+ series, status with icon + word.
- Reuse existing queries/hooks; no new endpoint unless a section has no data source (then say which).

## Verify

- vitest for each section's states (data, empty, unreadable, demo visitor).
- Screenshots at 1440 and 390, light and dark, member and recruiter, compared against the prototype.
