---
map: backend-etl
ticket: "15"
title: "Rent counts in the month it pays for: a late-month Housing charge moves to the next month"
type: build
status: built
status-detail: "Decided 2026-09-29 (Kevin: \"b\"), built the same day on feat/rent-month: compile and unit suite green. Phone only, a reading rule; no Room or server change. Owed: Kevin sees September's Money tile include the 2026-08-31 rent, with its sentence, on the A25."
blockers: ["14"]
blocked-by: ["[[14-ledger-rows-reach-the-phone]]"]
tags: [ticket]
---

# Rent counts in the month it pays for

## The problem (2026-09-29)

Rent is charged to the card as `RPS*The Pointe at V RD ...`, category Housing, usually a day or so
before the month it pays for (2026-07-30, 2026-08-31). Counted by calendar month, the Money tile
showed September with no rent and July and August with two.

## The decision

Offered:

- (a) leave as is;
- (b) "Treat a Housing charge in the last 3 days of a month as belonging to the next month."

**Kevin: "b".** Recorded in `memory/library/decisions.md` 2026-09-29.

## Built (2026-09-29, `feat/rent-month`)

- **One definition**, `budgetMonthOf` in `app/src/main/java/com/kevin/legion/ledger/BudgetMonth.kt` (beside `LedgerBudget.kt`): a row's calendar month (UTC),
  except an outflow whose category is `Housing` dated on or after `lengthOfMonth - 2`, which
  belongs to the next month (December into January of the next year).
- **Every spend-by-month figure reads it** through `budgetMonthRows`, which
  `LedgerController.monthPairingWindow` calls: `budgetVsActual`, `monthOperatingExpenses`,
  `categoryTransactions`, `monthlySpendTrend`. So the HOME Money tile, the Money screen's budget
  section and category drill-downs, `get_monthly_spend`, the CRED advisor digest, generated views
  and the spend trend all move together.
- **Transfer pairing is unchanged**: the pairing window stays on calendar dates, and
  `analyzeTransfers` is untouched.
- **Disclosed in words** (§4 rule 7): `BudgetVsActual.earlyChargesMoved` holds the rows moved in
  and out, and `earlyChargeSentences` is the one wording, e.g. "Includes a Housing charge of USD
  1,180.63 dated 2026-08-31, counted in September." and "A Housing charge of USD 1,180.63 dated
  2026-09-30 counts in October, not here." Only rows that really are spend are named.
- **Reading rule only.** `txnDate` is never changed. No Room migration, no server change.

## Verification

- Unit: `LedgerBudgetMonthTest` (day 29/30/31 of a 31-day month and 26/27/28 of February move, day
  28 of a 31-day month does not; non-Housing and inflows do not; December to January; September
  includes the Aug 31 rent and August excludes it; the sentences; pairing unaffected).
- Owed on the phone: September's Money tile includes the 2026-08-31 rent and states the sentence;
  August no longer shows two rents.

## Known limit

The daily spend bars are per calendar day and only draw days inside the month, so a moved-in row
has no bar in the month that counts it. The budget section's sentence states the move.
