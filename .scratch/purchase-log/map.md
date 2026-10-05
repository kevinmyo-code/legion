---
map: purchase-log
title: "The household bought log"
charted: 2026-10-04
charted-by: "Kevin + Opus"
effort: "`.scratch/purchase-log/`"
tickets: 9
open: 5
status: open
tags: [map]
---

# The household bought log

**Kevin, 2026-10-04:** *"wife wants to be able to keep track when we bought certain household
items. like shampoo etc. need a good system to track it. either from groceries checklist, or by her
manually logging in her own notepad either by voice or by hand."*

## Destination

**Shipped on the phone and the web, used by Mia.** Either of us can log "bought shampoo" by voice
or by hand, a tick on the Groceries list logs it by itself, and "when did we last buy shampoo?"
answers from real purchase entries on the phone, the web, and the web assistant
(`.scratch/web-assistant/`). Execution is in scope.

## Rulings at charting (Kevin, 2026-10-04)

- **A bought entry is its own record**, not a note and not a tick. It holds item, date and who
  (always), plus optional store, price, and quantity / note.
- **A tick on the Groceries list IS a purchase.** Ticking a Groceries line logs a bought entry for
  whoever ticked it, now. Every other list stays "ticked" under ADR 0049. ADR 0055 narrows 0049 for
  that one list and says so in words.
- **Shared by default, private optional.** The log is household-shared so either of us can ask; an
  entry may be marked private to the person who logged it (ADR 0052's ownership model).
- **Mia is on an iPhone, web PWA only.** Her voice path is the web assistant, not the Android app.

## What exists, so nothing here is invented (traced 2026-10-04 on origin/dev 59fec9b6)

- `checklist_ticks` (server) / `ChecklistTick` (Room): `day`, `ticked_at`, soft delete, unique
  `(item, day)`. **No column says who ticked.** A plain list counts an item done once any live tick
  exists; unticking soft-deletes it.
- The Groceries list is a plain checklist named "Groceries" (`grocery/GroceryChecklistMigration.kt`).
- ADR 0049 surfaces: phone voice `get_last_ticked`, `ui/checklists/LastTickedLabel.kt`,
  `checklists/TickMatch.kt` (deliberately narrow matcher). Nothing on the web.
- `grocery_staples` (`times_bought`, `last_bought_at`) exists on server, phone and web, and is NOT
  fed by ticks today.
- ADR 0052: only `events` and `checklists` carry `owner_user`; `household/tenancy.py` `visible()`
  is the choke point and covers `/mcp`.
- Engine `/mcp` tools: 11, none about purchases or tick history.
- No free-text notepad exists anywhere; web `/notes` is a read-only voice-note viewer.

## Notes

- CLAUDE.md §7 checklist binds every build here: a Django endpoint first (ADR 0044), tenancy via
  `visible()` and `TENANT_TABLES` and the leak test (ADR 0045), the phone reads Room and queues
  writes when the server is down and says so, every voice tool has a hands path (ADR 0035), failure
  results say what did not happen.
- **Price is what someone typed.** It is not reconciled against the bank and is never summed into
  ledger figures; every surface that shows it says it was entered by hand.
- Skills: `/grilling` for decisions, `/prototype` for 05 (clickable HTML, iPhone width for Mia, the
  A25's 384dp for the phone).

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | The bought entry: schema, tenancy, privacy, and the Groceries tick hook | - |
| 02 | decision | Matching: when is shampoo the same thing as Head & Shoulders? | - |
| 03 | decision | Backfill: do past Groceries ticks become bought entries? | 01 |
| 04 | decision | The phone's half: Room replica and sync, or engine-only | 01 |
| 05 | prototype | Where the log lives on screen, phone and web | 01 |
| 06 | build | Server: table, API, tenancy, the Groceries hook, `/mcp` tools | 01, 02, 03 |
| 07 | build | Web: the log screen, "log it" form, last-bought on list items | 05, 06 |
| 08 | build | Phone: voice tools, the log screen, last-bought label | 04, 05, 06 |
| 09 | test | Mia logs and asks, on her iPhone | 07 |

## Decisions so far

- [The bought entry](issues/01-the-bought-entry.md) - a typed `purchases` table; Groceries tick hook on the engine,
  same-day untick removes it; private entries via `owner_user`.
- [Matching](issues/02-matching.md) - loose search, always name the entry matched; never "never bought".
- [Backfill](issues/03-backfill.md) - past Groceries ticks imported, "logged by: not recorded".
- [The phone's half](issues/04-the-phones-half.md) - online only, by Kevin's ruling; server down said in words.

## Not yet specified

- "Running low" or restock nudges from purchase intervals (shampoo every ~6 weeks). Must pass the
  §7 compulsion test; its own map if wanted.
- Linking an entry to a ledger transaction (the price's real anchor).
- Store as a saved place.

## Out of scope

- Inventory / quantity on hand.
- Turning ticks on any list other than Groceries into purchases.
