---
status: accepted
decided: 2026-10-09
decided-by: Kevin
source: "[[decisions#2026-10-09 - Plaid is the truth for the BofA ledger, shown plain]]"
tags: [adr]
---

# 57. The bank's own feed is the ledger's truth for the accounts it covers

## Standing

ACCEPTED, built on `feat/plaid-ledger`, owing a first real link. For every account a household links
through Plaid (today: Kevin's Bank of America checking and cards), the rows Plaid returns are stored
**as fact with no verification**, provenance `BANK_API`, and shown exactly like a verified row. This
supersedes CLAUDE.md section 4 for those accounts only; section 4 still binds every other ingestion
path (pantry receipts, DBS statements, anything new).

**This reopens a locked decision, by Kevin, on purpose and narrowly.** [[0006-reconciliation-gate]]
is `locked` (CLAUDE.md section 2: "LLM ingestion is ALLOWED, behind a reconciliation gate"). Kevin
reopened it on 2026-10-09 for one source only, the bank's own transaction feed; 0006 carries the
amendment in its Standing line and is otherwise unchanged.

## Context

The BofA ledger arrived through a laptop script that downloaded activity CSVs and statements after
Kevin logged in by hand (backend-etl tickets 09 and 10). Card CSVs state no anchor, so most rows sat
UNRECONCILED and said "unverified" on every surface; the daily login was the bottleneck. Teller's free
tier died in 2026; Plaid's Trial plan gives real BofA data, checking and cards, free, with 10 lifetime
connections (the backend-etl research note `plaid-bofa-2026-10.md`, 2026-10-09). Kevin, 2026-10-09:
*"everything from plaid becomes truth, we retire manual parsers and csvs and statements etc. no need
to verify. automate both checking and cards, all of it"*. Asked how such rows should look on screen:
*"Plain, no tag at all"*.

## Decision

- **`BANK_API` provenance**, a fifth value of `public.provenance`. It exists so the source stays
  traceable in the database; no surface (web, phone, engine MCP, voice) renders it differently from a
  verified row. Money stays `Long` cents, converted from Plaid's float with `Decimal(str(x))`.
- **The engine pulls it**: `manage.py plaid_sync` every six hours, `/transactions/sync` with a stored
  cursor; added, changed, removed and pending-to-posted are applied in one transaction. The access
  token is sealed by the session vault (`LEGION_VAULT_KEY`) and never served.
- **The feed replaces the file-derived rows it covers.** Per account, from the earliest date Plaid
  returns, the CSV- and statement-derived rows are deleted; a category set on one moves to the
  matching bank row (same account, exact cents, within two days). A category with no match keeps its
  row and is reported, never dropped. Rows before Plaid's window stay as history, statement headers
  and their anchors included.
- **BofA files are retired from the Drive watcher**: `bofa_*` files and anything a BofA parser
  recognises are skipped and the run log says so. The parsers, `tools/bofa_pull.py` and
  `tools/legion-daily.cmd` are kept, marked retired, for history and as the way back.
- **One Plaid Item per household, never re-linked.** The web's Bank connection page offers only
  update mode ("Sign in again") for repairs, because removing an Item never frees a Trial slot.
- **Consent is said in words**: within 14 days of `consent_expiration_time`, or when Plaid reports
  ITEM_LOGIN_REQUIRED or kin, `/api/freshness` says "Bank connection needs you to sign in again" with
  the page that fixes it.

## Consequences

- The immutability trigger on `ledger_transactions` moved to `public.ledger_transactions_guard()`:
  UPDATE is still refused on every row; DELETE is allowed on UNRECONCILED and BANK_API rows, and on
  any row only inside a transaction that set `legion.bank_supersede = 'on'` (the replacement above).
- A changed bank row is a new row (new id), because the change feed keys this table on `created_at`.
- The enum value must be added by the type's owner before deploy
  (`supabase/migrations/20261009000100_provenance_bank_api.sql`); migration 0019 stops `migrate` in
  words if it is missing.
- If Plaid's Trial terms change, the way back is to switch the watcher's BofA retirement off
  (`ingest.statements.BOFA_RETIRED`) and run the laptop script again; the rows it would write are
  section 4 rows again.
