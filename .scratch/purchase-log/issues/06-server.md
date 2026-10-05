---
map: purchase-log
ticket: "06"
title: "Server: table, API, tenancy, the Groceries hook, /mcp tools"
type: build
status: open
status-detail: ""
blockers: ["01", "02", "03"]
blocked-by: ["[[01-the-bought-entry]]", "[[02-matching]]", "[[03-backfill]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Server: table, API, tenancy, the Groceries hook, /mcp tools

## Build

Per 01-03: the Django model and migration, REST endpoints, `TENANT_TABLES` + `visible()` + leak test,
the Groceries tick hook (create, same-day untick deletes), the backfill if ruled, and `/mcp` tools
(`log_purchase`, `last_bought`, `list_purchases`, `delete_purchase`) whose results say in words what
did not happen. pytest green.
