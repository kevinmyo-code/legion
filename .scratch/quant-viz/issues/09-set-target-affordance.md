# 09 - SET TARGET affordance in the category drilldown

Status: OPEN. Follow-on: Kevin asked (2026-08-13) "set a budget target so i can see the meters".
The legion-shape ticket 06 decision D9 said targets are "set by voice AND screen"; only the voice
half (`set_budget` in LiveToolbox) was ever built. This is the missing screen half, minimal.

## Spec

- Placement: `ui/ledger/LedgerCategoryDrilldown.kt`, at the TOP of `CategoryDrilldownScreen`
  under the header, ONLY when the drilldown is open on a real category (never for the
  `(uncategorised)` bucket - it has no target by D11, same reason it has no meter).
- Pattern: mirror `AddCategoryRow` (`ui/ledger/BudgetSection.kt:230`) exactly - local text state,
  `OutlinedTextField` + `TextButton("SET")`, error text from the state holder that survives
  recomposition, typed text cleared only on a confirmed write via a success nonce.
- Current target shown next to the field in words: `"target <formatMoney(...)> since <month>"` or
  `"no target set"` (the affordance states what it is changing).
- Input is DOLLARS text (e.g. `300` or `299.99`). Parse WITHOUT Double: split on `.`, dollars part
  and 0-2 digit cents part each `toLongOrNull()`, `cents = dollars * 100 + centsPart` (pad one
  digit cents: `"5"` -> 50). Reject: blank, negative, non-numeric, more than 2 decimal places,
  result over 9_999_999_99 cents. Rejection message in words next to the field, typed text kept.
- Write: `LedgerController.setBudget(context, entity, category, month = the drilldown's own open
  month, amountCents)`. Setting from the OPEN month is the honest reading of "copy forward" - the
  target applies from the month Kevin is looking at, onward.
- After a confirmed write the state holder must refresh `budgetVsActual` so the meter appears
  without leaving the screen (same refresh path the add-category flow already uses in
  `ui/LedgerScreen.kt`).
- A target of `0` is a valid explicit write (it silences the meter via the existing
  `target > 0L` guard, by design) - allow it, but the words next to the field then read
  `"target USD 0.00 - no meter is drawn at zero"`.

## Verification

- [ ] Unit test the dollars-text -> cents parser (pure function, exact Longs: `"299.99"` ->
      29999, `"300"` -> 30000, `"5.5"` -> 550, rejects `"1.234"`, `"-3"`, `""`, `"abc"`).
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] Uncategorised bucket shows no affordance.
- [ ] On-device: set a real target through the new UI; meter renders in BudgetSection.
