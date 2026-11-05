---
map: household-finance
ticket: "06"
title: "Computed values: bonds, loan, flat, CPF interest, car"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01", "03"]
blocked-by: ["[[01-accounts-and-snapshots]]", "[[03-fx-rates]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Computed values: bonds, loan, flat, CPF interest, car

## What
All labelled **estimate** until a statement confirms them (§4 rule 5).
- Bonds (SSB): issue codes and amounts entered once. Value = principal + accrued coupon from MAS's published step-up rates. Redemption value = principal.
- HDB loan: amortise from the last confirmed balance, rate and instalment. A yearly HDB statement (ticket 04) re-anchors it.
- HDB flat: median of recent resale transactions for the configured town, flat type and storey band, from data.gov.sg's resale dataset (open API). Store the comparables used.
- CPF: monthly interest per sub-account at CPF's published rates between statements. Confirmed by the yearly statement.
- Car: a manual value with a confirm-by date.
## Verification
- pytest per calculator with fixed inputs, and an estimate flag on every row.
