---
map: household-finance
ticket: "04"
title: "Statement drops for the other accounts"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01"]
blocked-by: ["[[01-accounts-and-snapshots]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Statement drops for the other accounts

## What
- Narrow ADR 0057's BofA skip: skip a BofA file only when its account last4 is Plaid-linked. Other BofA accounts go through the normal gate (deterministic BofA parser if it recognises the file, else quarantine; never Gemini for a CSV).
- SGD bank and card statements: the existing Gemini path with 3 anchors. Add a card-statement shape (statement balance, previous balance, payments, new charges) and its anchor maths.
- CPF statement: per-account (OA/SA/MA/RA) opening, contributions, withdrawals, interest, closing. Each sub-account reconciles. Writes `BalanceSnapshot` rows, not ledger transactions.
- HDB loan statement: outstanding principal and instalment. Writes snapshots.
- Brokerage and bond holdings statements, if dropped: snapshots only.
- Each file is matched to a `FinanceAccount` by `statement_match`. An unmatched file quarantines with "no account matches".
- An ADR 0057 amendment note goes into `docs/adr/` (the ADR text keeps its history).
## Verification
- pytest with synthetic statements for each shape: pass, quarantine on a missing anchor, quarantine on a mismatch, anchors persisted.
