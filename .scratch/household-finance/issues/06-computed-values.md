---
map: household-finance
ticket: "06"
title: "Computed values: bonds, loan, property, scheme interest, vehicle"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["01", "03"]
blocked-by: ["[[01-accounts-and-snapshots]]", "[[03-fx-rates]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Computed values: bonds, loan, property, scheme interest, vehicle

## What
All labelled **estimate** until a statement confirms them (§4 rule 5).
- Bonds: issue codes and amounts entered once. Value = principal + accrued coupon from the issuer's published step-up rates. Redemption value = principal.
- Housing loan: amortise from the last confirmed balance, rate and instalment. A yearly loan statement (ticket 04) re-anchors it.
- Property: median of recent resale transactions for the configured locality, type and band, from the public open-data portal's resale dataset (open API). Store the comparables used.
- Retirement scheme: monthly interest per sub-account at the scheme's published rates between statements. Confirmed by the yearly statement.
- Vehicle: a manual value with a confirm-by date.
## Verification
- pytest per calculator with fixed inputs, and an estimate flag on every row.
