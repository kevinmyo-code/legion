# 02 - Budget line meters

Status: OPEN. Lane A (ledger). Depends on 01 (branch state only; uses no new primitive).

## What

Each `BudgetLineRow` in `ui/ledger/BudgetSection.kt` gains a `DeckMeter` under its existing two
text lines. The text does not move or shrink - the meter is added, never a replacement (map
taste call 4).

## Spec

- `DeckMeter(fraction, paceFraction)` from `ui/common/DeckPanels.kt:218`.
- `fraction = line.gap.actual.toFloat() / line.gap.target.toFloat()`, coerced into 0f..1f by
  DeckMeter itself. Guard: when `line.gap.target <= 0L`, render NO meter (a meter against no
  target is a lie; the row's text already carries the numbers).
- `paceFraction`: only when `month == YearMonth.now(zone)` - `dayOfMonth / lengthOfMonth` as
  Float, the green "where you should be by today" tick. Past and future months pass `null`
  (a pace tick on a closed month marks nothing).
- Over-budget rows (`gap.gap < 0`): fraction clamps to 1f (full bar) and the existing "over"
  word plus debit-colored amount already say it in words. No new color logic in the meter.
- `TrustTier.REPORTED` lines keep the meter; the row's existing `sem.estimated` coloring and
  `provisionalLabel` words stay as the disclosure. Do not invent a second meter style.
- `UncategorizedRow` gets NO meter - no target exists (D11's own reasoning), and drawing one
  against an implied target is exactly the kind of chart-lie D11 exists to block.
- Spacing: 4.dp between the text block and the meter; meter spans the row width minus the
  existing horizontal padding.
- Update `BudgetSection`'s `@Preview`s (if present) to include an over-budget line and a
  zero-target line.

## Verification

- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] Zero-target guard present (grep for `target <= 0` or equivalent in the diff).
- [ ] Every existing disclosure sentence in BudgetSection still rendered (diff shows no deleted
      Text with §4 wording).
- [ ] On-device (map-level): meters visible on Kevin's real month; over-budget category shows a
      full bar with "over" wording intact.
