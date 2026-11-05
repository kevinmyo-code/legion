---
map: household-finance
ticket: "02"
title: "Household-internal money: spouse transfers, cross-border wires, card payments"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01"]
blocked-by: ["[[01-accounts-and-snapshots]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Household-internal money: spouse transfers, cross-border wires, card payments

## What
- Ruling 10: a transfer between the two spouses is household-internal.
  - Extend `api/spend.py` analysis with a pass that marks a row internal when its counterparty matches a household member's configured payer/payee names (stored on the member, loaded from the private file; never hard-coded).
  - Both legs are paired when both are present; when only one side is visible, the row is still internal.
- Cross-border wires between household accounts (US <-> SG) are internal: pair by amount within FX tolerance and a 10-day window once both sides exist; otherwise mark by configured remitter names.
  - Wire fees stay spend (category Fees).
- Card payments are already netted. Confirm with the real description shapes from Plaid and add tests.
- Every internal row says why (`internal_reason`), so it is auditable.
## Verification
- `tests/test_spend_parity.py` still green (or the phone's parity test is consciously updated with a note).
- New tests use synthetic names only.
