---
map: purchase-log
ticket: "03"
title: "Backfill: do past Groceries ticks become bought entries?"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-the-bought-entry]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Backfill: do past Groceries ticks become bought entries?

## Question

The Groceries list already has tick history. ADR 0055 makes a Groceries tick a purchase from now on.
Do past Groceries ticks get imported as bought entries (with "who" unknown, since ticks never
recorded it), or does the log start empty on the day it ships?
