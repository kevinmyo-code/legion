---
map: purchase-log
ticket: "03"
title: "Backfill: do past Groceries ticks become bought entries?"
type: decision
status: resolved
status-detail: "Kevin: import past Groceries ticks, who not recorded"
blockers: ["01"]
blocked-by: ["[[01-the-bought-entry]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Backfill: do past Groceries ticks become bought entries?

## Question

The Groceries list already has tick history. ADR 0055 makes a Groceries tick a purchase from now on.
Do past Groceries ticks get imported as bought entries (with "who" unknown, since ticks never
recorded it), or does the log start empty on the day it ships?

## Answer (2026-10-04)

**Kevin: import.** Every live (not deleted) past tick on the Groceries list becomes a bought entry
with `source = GROCERIES_BACKFILL`, `bought_on` = the tick's day, item = the item's current text,
`created_by` = null, shown as "logged by: not recorded". One-time, idempotent (keyed on the tick),
run as a data migration.

Caveat carried from ADR 0049: an item whose text was edited since the tick imports under its new
text. Stated in the migration's docstring; not fixable without text-on-tick history.
