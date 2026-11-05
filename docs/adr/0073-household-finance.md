---
status: accepted
decided: 2026-11-05
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-05 - Household finance: layers, internal transfers, floors, estimates (Kevin)]]"
tags: [adr]
---

# 73. Household finance: layers, internal transfers, floors and estimates

## Standing

**The engine holds the whole household's money across two countries, and Alfred tracks, projects
and warns; he never moves money.** Accounts, dated balances, the day's USD/SGD rate, the income
plan and the limits Kevin set live in `server/finance/`. The real figures never enter the public
repo: they are loaded from a private file outside it (`manage.py load_household_finance`).

- **Layers** (map ruling 2): liquid (bank accounts less cards), investments, CPF (locked),
  property (flat less loan, locked), other. Runway counts liquid and investments only.
- **Money is int cents; a rate is int `rate_micros`**, rounded half to even. **Debt is negative.**
  The headline is USD; the SGD view, and the USD view of SGD accounts, use today's stored rate (the
  nearest earlier day's when today has none, never a later day's). A figure that needs a rate when
  none is stored is refused in words, never converted at a guess. Read-only surfaces never fetch a
  rate; `manage.py fx_rates` does, daily.
- **Household-internal money is not spend** (map ruling 10). It is a layer ABOVE the ported spend
  rules (`api/spend.py`, the phone's Kotlin, kept cent for cent by `tests/test_spend_parity.py`),
  never inside them. A row is internal when it is shaped like a transfer AND names a member's
  configured payer name (both: a bank prints the holder's name on payroll too), or when a USD and
  an SGD transfer leg of opposite sign, within 10 days, agree within 3% at the earlier day's stored
  rate. A fee or charge line never is. Every internal row says why.
- **Floors** (map ruling 4): test A, no burn, is planned income covering spend plus the mortgage;
  test C is a household liquid floor, and a US top-up trigger: checking less what is owed on the
  card covers the next 30 days of US bills, warned before the card's due day.
- **Safe to spend** (Kevin, 2026-11-05), the main daily money figure: checking, less everything
  owed on the card (current balance plus pending charges), less the US fixed bills due before the
  next expected pay date. A top-up is needed when it is below zero or the top-up trigger is short,
  by the pay date less the warning days.
- **Anything worked out is an estimate, and says so** (CLAUDE.md section 4 rule 5): a computed
  balance, every projection, the plan's pay dates, a detected bill or subscription. A balance from
  the bank feed or a gated statement is fact.

## Context

Kevin is a student until full-time work starts, his household's money sits in two countries, and
a transfer from Singapore takes days. Plaid covers only his two US accounts; everything else
arrives monthly. The repo and its wiki are public, so the mechanism is public and the figures
cannot be.

## Decision

One engine app, read-only surfaces and pull-based tools (`net_worth`, `cash_flow`, `runway`,
`safe_to_spend`, `budgets`), every figure computed once in Django and shared by the routes and the
tools. Definitions this ADR fixes that Kevin did not rule on, so a future change is a change here:

- **Runway** projects each side month by month for two years from the income plan and the average
  netted spend of the last 3 FULL months, plus the mortgage while it is not in the ledger. It names
  the month household liquid falls below the floor, on the expected plan and on the fallback plan
  (as if every expected income never came), and the cross-border transfer the US side needs to stay
  above its own floor (the next 30 days of US bills) through every gap month: the largest
  shortfall, by the first day of the first short month less `transfer_lead_days`.
- **Wave 1's spend is the ledger's**: only accounts with ledger rows. Every body names the liquid
  accounts it cannot see.
- **The card's statement balance is not on the bank feed**, so its current balance (never smaller
  once a statement has closed) stands in for it, labelled an estimate.
- **A recurring merchant** is the first three words of the description, seen in 3 or more months,
  amounts within 15% of the median, charges 25 to 35 days apart. A subscription is new when its
  merchant first appears this month in the Subscriptions category.
- **Budget pace**: the marker is cap x days elapsed / days in the month; a projection is given only
  from day 10.

## Consequences

- The privacy guard has two layers: `tests/test_finance_privacy.py` holds every finance file to
  the synthetic household's figures, and `tools/finance_privacy_check.py <private file>` searches a
  branch's added lines and commit messages for the real file's figures, names and account numbers.
  The first can be fooled by adding a real figure to the synthetic list; the second catches that.
- `GET /api/ledger/spend` now differs from the phone's Money screen by exactly
  `excluded.household_internal_cents` until the phone reads the engine's figure.
- The alerts (wave 3) are bound by CLAUDE.md section 7's compulsion test and by map ruling 6: at
  most one spoken money alert a day.
