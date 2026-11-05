---
map: household-finance
title: "Household finance: net worth across two countries, cash flow, budgets, and Alfred's money alerts"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/household-finance/`"
tickets: 14
open: 14
status: open
tags: [map]
---

# Household finance

**Kevin, 2026-11-05:** *"expand the finance sources to the entire household's networth and draw
alerts analyses and budgeting. with alfred proactively telling me hey youve spent too much, or ur
approaching ur grocery budget, u might have to transfer money over from singapore etc."* The goal:
hold net worth steady until Kevin graduates and starts full-time work.

**This repo is public.** Every figure, account, income and balance lives in a private file outside
the repo (`legion-household-finance.md` in Kevin's Documents), loaded into Postgres by
`manage.py load_household_finance <file>` (ticket 12). This map and its tickets describe mechanisms
only. A test fails if a household figure appears in `server/` fixtures outside synthetic test data.

Alfred **tracks, projects and warns** against limits Kevin set. He never moves money and never picks
investments. Moving money is Kevin's job; an alert names the shortfall and the date, nothing more.

## Shape (Kevin, 2026-11-05)

- **One household:** every account is visible to both spouses. No private finance rows.
- **Two countries, two currencies:** USD and SGD. Accounts sit in US and Singapore banks, plus a brokerage, government savings bonds, CPF, an HDB flat and its loan, and a car.
- **Net worth layers:**
  - **liquid:** bank accounts minus cards
  - **investments:** brokerage, bonds
  - **CPF:** locked
  - **property:** flat minus loan, locked
  - **other:** car
- **Runway** uses liquid + investments only.
- **Income plan:** a job ending on a known date, an expected restart (with a fallback), steady rental income, and a small steady second income. All of it is in the private file.

## Rulings (all Kevin, 2026-11-05)

1. **Sources.**
   - Kevin's two everyday US accounts stay live on Plaid.
   - Every other account is a monthly statement drop ("slow moving").
   - ADR 0057's "skip every BofA file" narrows to "skip a BofA file whose account is Plaid-linked", so the spouse's BofA statements are read and gated.
2. **Layers** as above. The runway counts liquid + investments.
3. **Currency.**
   - The headline is USD, with an SGD view at today's rate.
   - Each day's rate is stored, so a past figure can be re-checked.
4. **The test (A + C).**
   - A, no burn: monthly household income covers household spend plus the mortgage.
   - C, two floors:
     - a household liquid floor (3 months of essentials)
     - a US top-up trigger: Kevin's checking minus what's owed on the card covers the next 30 days of US bills plus the card's statement balance by its due date, warned 7 days ahead, because a cross-border transfer takes days
   - Amounts are in the private file.
5. **Budgets:** monthly USD caps for groceries, subscriptions, shopping, dining, pets and transport. Rent, utilities and insurance are fixed and only checked for being paid. Amounts are in the private file.
6. **Alerts:** at most ONE spoken money alert a day, at a natural pause, never mid-conversation. The rest go to a notification and the morning briefing. The set:
   - category pace
   - 80% and 100% of budget
   - US top-up
   - household floor
   - unusual charge or new subscription
   - statement missing on the 5th
   - monthly review on the 1st
7. **Statement fetch:**
   - The Claude worker may open bank and CPF sites in Chrome AFTER Kevin signs in himself, and download statements into the Drive folder.
   - Read and download only: no transfers, no settings.
   - It never types a password.
8. **Brokerage:** the engine may store the brokerage's read-only report token (IBKR Flex), encrypted with the vault key like Plaid's.
9. **Automation tiers:**
   - automatic: brokerage, FX, flat value, bonds, loan amortisation, CPF interest
   - one sign-in a month: bank and CPF statements, fetched by Claude
   - manual: anything left
10. **Transfers between spouses** count as household-internal, not spend.

## What exists (dev `b6912cc3`)

- **Spend.** `api/spend.py` (`GET /api/ledger/spend`) nets transfers: equal-and-opposite pairs within 5 days, own-account references, and not-spending categories.
  - A Zelle to a person still counts as spend (pass 3 only flags it). Ruling 10 changes that for the spouse (ticket 02).
- **Statements.**
  - SGD statements are read by Gemini behind the 3-anchor gate (`ingest/statements.py`, CLAUDE.md §4).
  - `BudgetTarget(category, currency, effective_from_month)` exists and is unused.
- **Plaid balances** are stored on `PlaidItem.accounts` (`api/ledger_balances.py`).
- **Not built:** FX rates, assets, liabilities, net worth, income plan, runway, alerts.
- **Data gap:** a large share of US outflow is uncategorised, mostly card payments and spouse transfers.

## Tickets

| # | Ticket | Where | Blocked by |
|---|---|---|---|
| 01 | [Household accounts and balance snapshots](issues/01-accounts-and-snapshots.md) | engine | - |
| 02 | [Household-internal money](issues/02-household-internal-money.md) | engine | 01 |
| 03 | [Daily FX rates](issues/03-fx-rates.md) | engine | - |
| 04 | [Statement drops for the other accounts](issues/04-statement-drops.md) | engine | 01 |
| 05 | [IBKR Flex connector](issues/05-ibkr-flex.md) | engine | 01, 03 |
| 06 | [Computed values: bonds, loan, flat, CPF interest, car](issues/06-computed-values.md) | engine | 01, 03 |
| 07 | [Net worth, cash flow, runway, income plan](issues/07-net-worth-and-runway.md) | engine | 01, 02, 03 |
| 08 | [Budgets and pace](issues/08-budgets-and-pace.md) | engine | 02 |
| 09 | [Money alerts](issues/09-money-alerts.md) | engine + phone | 07, 08 |
| 10 | [Alfred and the Money screen](issues/10-alfred-and-screens.md) | engine + phone + web | 07, 08 |
| 11 | [Statement fetch skill for the Claude worker](issues/11-statement-fetch-skill.md) | worker | 04 |
| 12 | [Private loader and Kevin's setup list](issues/12-private-loader-and-setup.md) | engine + Kevin | 01 |
| 13 | [Home: Money panel replaces the calendar](issues/13-home-money-panel.md) | phone | 07, 08 |
| 14 | [Money screens: month swipe, read-only accounts, budget bars](issues/14-money-screens-revamp.md) | phone | 13 |

Order:
1. Wave 1, engine only: 01, 12, 03, 02, 07, 08. That gives the US top-up trigger and budgets from Plaid data alone.
2. Wave 2: 04, 05, 06. These fill the household.
3. Wave 3: 13 and 14 first (Kevin's priority), then 09, 10 (phone, one Gradle at a time) and 11.

ADR 0073 is written with 01, 07 and 09.

## Rules this map keeps

- §4:
  - every new ingestion path goes through the gate (3 anchors for anything an LLM reads)
  - anything computed is labelled an **estimate**
  - money is int cents
  - anchors are persisted
- Pending bank rows are said as pending.
- §7 compulsion test for every alert: anchored to a checkable figure, actionable, never about absence, silenceable in one sentence.
- Every voice capability has a hands path (Money screen).
- No household figure, account number or balance in the public repo.
