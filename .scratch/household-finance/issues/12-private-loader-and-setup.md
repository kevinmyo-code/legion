---
map: household-finance
ticket: "12"
title: "Private loader and Kevin's setup list"
type: build
status: built
status-detail: "built 2026-11-05. Owes: Kevin adds the yaml block (payer names, income plan, floors, top-up, budgets) to his private file and runs the loader after deploy; setup steps 2-5 wait on wave 2"
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
  2. A second member creates a broker Flex query and a read-only token; Kevin stores them with `manage.py set_ibkr_flex`.
  3. Enter the bond issue codes, the housing-loan rate and the latest balance, and the property's locality, type and band.
  4. First statement drop: the second member's domestic bank, both overseas banks, and both retirement sub-accounts.
  5. Confirm the vehicle value.
## Verification
- pytest: the loader is idempotent and its output carries no figures.
