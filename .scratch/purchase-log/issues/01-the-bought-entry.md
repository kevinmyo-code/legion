---
map: purchase-log
ticket: "01"
title: "The bought entry: schema, tenancy, privacy, and the Groceries tick hook"
type: decision
status: resolved
status-detail: "Kevin's rulings plus Opus's proposals, Kevin may veto"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer (2026-10-04)

Kevin ruled the shape (fields, shared with optional private, Groceries tick = bought, ADR 0055).
The rest is Opus's proposal on the same day, built unless Kevin vetoes:

- **A typed Django table `purchases`**, not an aspect-engine record type: the engine has no server
  half, and Mia's PWA must see it.
- **Columns:** `item` (text as written), `bought_on` (local epoch day), `logged_at` (instant),
  `created_by` (nullable: null = "not recorded", only for backfilled rows), `store` (nullable text),
  `price_cents` (nullable `Long`, entered by hand), `quantity_note` (nullable text), `owner_user`
  (null = shared, set = private to that member), `source` (`MANUAL` | `GROCERIES_TICK` |
  `GROCERIES_BACKFILL`), `tick` (nullable FK to `checklist_ticks`), `deleted_at`, `sync_id`,
  `household`.
- **Tenancy:** in `TENANT_TABLES`, read through `visible()` (owner rule as ADR 0052), leak-tested,
  `/mcp` included. A private entry is invisible to the other member on every surface.
- **The Groceries hook:** server-side, when a tick is created on the household's Groceries list
  (from phone sync or the web), create one `GROCERIES_TICK` entry: item = the item's text at that
  moment, `bought_on` = the tick's `day`, `created_by` = the request's user, shared. **Untick on the
  same local day soft-deletes the entry the tick created; a later untick leaves it.** Re-tick the
  same day revives it rather than duplicating.
- **"The Groceries list"** = the household's checklist whose name is "Groceries" (case-insensitive,
  trimmed), shared, not deleted. If there are several, the oldest. Renaming it ends the hook, said in
  the list's settings.
- **Price** is shown everywhere as "entered by hand" and is never summed into ledger figures.
