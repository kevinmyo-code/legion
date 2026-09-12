---
map: web-surface
ticket: "03"
title: "Desktop is not the phone reflowed: what the workbench shows that the PWA never does"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Desktop is not the phone reflowed

**Kevin, 2026-09-12:** *"computer brwoser is for me to edit and review data etc. ledger ingestion all
that. pwa for wife"*.

Today the desktop renders the phone's column into `max-w-lg` inside a 1707px window. About 70% of the
screen is empty and the rail holds two items. **It cannot review or edit anything.**

## The question

**What does the workbench show that Mia's phone never does?**

One account, one API, two jobs. The phone answers *what do I need to do today*. The desktop answers
*what is coming, what needs a decision, and where do I go to fix a record* - three questions, which is
why one column cannot carry it.

## What is already decided and constrains this

- **Family-first visual language** (2026-09-12). The desktop being denser does not make it
  mission-control; that language stays phone-only.
- **Mia never sees admin.** No tables, no provenance tags, no ingestion controls on the PWA. Whatever
  this ticket adds is desktop-only by construction, not by breakpoint accident.
- **`docs/design/canvas/` already drew a candidate**: a left rail with a Records section
  (Ledger/Pantry/Fleet/Places), a two-column body with the day on the left and a "Needs you" panel
  plus an ingest dropzone on the right. That is a proposal, not a ruling.

## What the decision must settle

1. **Does the rail carry the aspects now**, before their screens exist? A rail item that opens an
   empty page is worse than no rail item; a rail that grows one item per ticket never reads as a
   product.
2. **What is on the dashboard beside the day.** Candidates from real data: unreconciled ledger rows
   (the §4 disclosure has to live somewhere), the week's shape (ticket 01), recent ingestion results,
   and what Mia ticked. Pick few.
3. **Where ingestion lives.** A dropzone on the dashboard, a Ledger screen, or both. `web-and-households`
   06 assumed a Ledger screen; the canvas drew a dropzone on Today.
4. **Whether the desktop gets write access to checklists and events, or read-only plus records.**
   Kevin said "edit and review data"; that is unambiguous for records and silent about the day.

## Not in scope

The aspect screens themselves - `web-and-households` 06 owns those, and ticket 07 re-briefs it once
this is settled.
