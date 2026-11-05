# Household finance: private facts (TEMPLATE, invented household)

This is the SHAPE of the private file `manage.py load_household_finance` reads
(household-finance ticket 12, ADR 0073). Every name and figure below is
invented. Keep the real file OUTSIDE the repo; the loader refuses a path inside
it. Prose anywhere in the file is ignored. Two parts are read:

1. the `## Accounts` table, as written;
2. the one fenced `yaml` block, for what a table cannot carry.

Money is in major units (`500` is 500.00), at most two decimal places.

## Accounts

| Key | Account | Owner | Ccy | Layer | Source |
|---|---|---|---|---|---|
| us-checking-1000 | Bank A checking 1000 | Alex | USD | liquid | plaid |
| us-card-7823 | Bank A card 7823 | Alex | USD | liquid (debt) | plaid |
| sg-savings | Bank B savings | Sam | SGD | liquid | statement |
| sg-card | Bank C card | Sam | SGD | liquid (debt) | statement |
| sg-bonds | Savings bonds | Sam | SGD | investments | computed |
| brokerage | Broker D (ETF) | Sam | USD | investments | ibkr-flex |
| pension-alex | Pension fund | Alex | SGD | cpf | statement |
| flat | Flat | joint | SGD | property | computed (resale prices) |
| flat-loan | Flat loan | joint | SGD | property (debt) | computed, confirmed yearly |
| car | Car | Alex | USD | other | manual |

## Machine-read block

```yaml
members:
  alex:
    label: Alex
    # The names the bank prints when money moves to or from this person
    # (a Zelle line, a wire's remitter). Matched case-insensitively.
    payer_names: [ALEX RIVER]
    # Optional: links the member to their LEGION sign-in.
    # user_email: alex@example.com
  sam:
    label: Sam
    payer_names: [SAM RIVER, S RIVER]

# Optional per-account settings the table cannot carry.
accounts:
  sg-savings:
    statement_match: "Bank B savings"
  brokerage:
    country: SG

income:
  - key: alex-job
    member: alex
    label: Alex's job
    amount: 2000
    currency: USD
    cadence: monthly        # weekly | biweekly | monthly | yearly | once
    start: 2026-01-01
    end: 2026-11-30
    confidence: confirmed   # confirmed | expected | fallback
    account: us-checking-1000
  - key: alex-next-job
    member: alex
    label: Alex's next job
    amount: 3000
    currency: USD
    cadence: monthly
    start: 2027-03-01
    confidence: expected
    account: us-checking-1000
  - key: alex-fallback
    member: alex
    label: Alex's fallback
    amount: 1000
    currency: USD
    cadence: monthly
    start: 2027-01-01
    confidence: fallback
    account: us-checking-1000
  - key: rent-in
    member: sam
    label: Rent received
    amount: 2500
    currency: SGD
    cadence: monthly
    confidence: confirmed
    account: sg-savings

policy:
  currency: USD
  liquid_floor: 10000
  mortgage:
    amount: 1000
    currency: SGD
    in_ledger: false        # true once the account that pays it is in the ledger
  us_topup:
    checking: us-checking-1000
    card: us-card-7823
    bills_30d: 2000
    warn_days: 7
    card_due_day: 20        # optional, day of month
  transfer_lead_days: 7
  fixed_categories: [Housing, Utilities, Insurance]
  # Optional: the US fixed bills safe-to-spend holds back before the next
  # income date. Without this list they are worked out from the fixed
  # categories' recurring merchants (an estimate).
  fixed_bills:
    - {label: Rent, amount: 1000, day: 1}
    - {label: Power, amount: 100, day: 15}

budgets:
  currency: USD
  from: 2026-11             # first month these caps apply
  caps:
    Groceries: 500
    Dining: 100
    Subscriptions: 50
```
