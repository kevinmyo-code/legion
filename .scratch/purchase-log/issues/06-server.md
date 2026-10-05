---
map: purchase-log
ticket: "06"
title: "Server: table, API, tenancy, the Groceries hook, /mcp tools"
type: build
status: built
status-detail: "Server built, pytest 1437 passed / 44 skipped; owes the deploy and a live run"
blockers: ["01", "02", "03"]
blocked-by: ["[[01-the-bought-entry]]", "[[02-matching]]", "[[03-backfill]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Server: table, API, tenancy, the Groceries hook, /mcp tools

## Build

Per 01-03: the Django model and migration, REST endpoints, `TENANT_TABLES` + `visible()` + leak test,
the Groceries tick hook (create, same-day untick deletes), the backfill if ruled, and `/mcp` tools
(`log_purchase`, `last_bought`, `list_purchases`, `delete_purchase`) whose results say in words what
did not happen. pytest green.

## Built (2026-10-04, feat/purchase-log)

- `server/purchases/`: `public.purchases` (migration 0001, owner guard attached), the backfill
  (0002), REST at `/api/purchases/` (list, create, `<id>` get/patch/delete, `last-bought?q=`),
  ticket 02's matcher (`matching.py`), the Groceries hook (`groceries.py`).
- The hook runs from `checklists/views.py`'s tick POST (create and revive) and untick DELETE, the
  only two tick write paths; phone, web and `/mcp` all reach them. The untick takes `?today=<local
  epoch day>`; without it the engine falls back to the UTC date (see `groceries.py`).
  **Superseded in part 2026-10-05 (Kevin): the household has a timezone.** "Today" is now the
  household's zone first (server clock), then `?today=`, then UTC - `groceries.untick_today`,
  tests in `tests/test_household_timezone.py`. The owner sets it once; until then nothing changes.
- `/mcp`: `log_purchase`, `last_bought`, `list_purchases`, `delete_purchase`. Existing tools
  unchanged.
- In `TENANT_TABLES` and `OWNER_PATHS`; member removal tombstones private entries. Leak tests in
  `test_tenancy.py`, member privacy in `test_visibility.py`, the rest in `test_purchases.py`.

Owed: deploy (two migrations, no `private.*`), and a live check that the backfill imported the
real Groceries ticks.
