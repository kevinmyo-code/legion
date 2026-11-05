---
map: household-finance
ticket: "12"
title: "Private loader and Kevin's setup list"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01"]
blocked-by: ["[[01-accounts-and-snapshots]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Private loader and Kevin's setup list

## What
- `manage.py load_household_finance <path>`: reads Kevin's private markdown/YAML (outside the repo), then upserts accounts, members' payer names, the income plan, budgets, floors and triggers. Idempotent. Prints a summary with no figures.
- A guard test: no fixture under `server/` contains a household-specific figure (synthetic data only).
- Kevin's setup list:
  1. Run the loader against `Documents/legion-household-finance.md`.
  2. His wife creates an IBKR Flex query and a read-only token; Kevin stores them with `manage.py set_ibkr_flex`.
  3. Enter the SSB issue codes, the HDB loan rate and the latest balance, and the flat's town, type and storey band.
  4. First statement drop: her BofA, both DBS, Citi SG, both CPF.
  5. Confirm the car value.
## Verification
- pytest: the loader is idempotent and its output carries no figures.
