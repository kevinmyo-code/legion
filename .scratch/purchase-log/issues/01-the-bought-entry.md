---
map: purchase-log
ticket: "01"
title: "The bought entry: schema, tenancy, privacy, and the Groceries tick hook"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The bought entry: schema, tenancy, privacy, and the Groceries tick hook

## Question

Settle the record before anyone builds it.

- **Table:** a typed Django table (e.g. `purchases`) vs a record type in the aspect engine. The
  engine has no server half (engine-mcp map), so a typed table is the likely answer; say why.
- **Fields:** item text, bought-on date (a local day, plus the instant it was logged), `created_by`
  (who), optional store, price (`Long` cents, labelled as entered), quantity / note, `owner_user`
  (null = shared, set = private, ADR 0052), source (`MANUAL` | `GROCERIES_TICK`), and a link to the
  tick that created it, if any. Soft delete, `sync_id`, household.
- **Tenancy:** in `TENANT_TABLES`, through `visible()`, covered by the leak test; private entries
  invisible to the other member everywhere, `/mcp` included.
- **The Groceries hook (ADR 0055):** server-side when a tick on the list named Groceries is created
  (phone sync or web), so both clients get it from one place (ADR 0044: a business rule lives in
  Django once). Who = the authenticated user of the request that created the tick. **Untick
  within the same day deletes the entry it created; an untick later does not** (an old tick being
  cleared is not "I did not buy it"). Confirm or change.
- What "the Groceries list" means if it is renamed or there are two.
