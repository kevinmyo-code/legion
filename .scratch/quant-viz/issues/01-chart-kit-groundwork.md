# 01 - Chart-kit groundwork: bucketDailySumCents + DeckSmallMultiple

Status: OPEN. Blocks every other quant-viz ticket. No screen changes here.

## Part A: `bucketDailySumCents` (pure, `ui/common/DeckChartData.kt`)

The kit has `bucketDailyAverage` (right for readings: weight, voltage). Spend needs a SUM, in
Long cents, with the ledger's own coverage-aware gap rule (map taste call 3).

```kotlin
/**
 * Folds raw (timestampMs, amountCents) samples into one Long-cents sum per local day in
 * [startMs, endMs]. A day inside at least one of [coveredRanges] with no samples is 0L - a
 * genuine zero. A day outside every covered range is null - a GAP (the file doc's invariant
 * read onto money: "no statement covered this day" and "nothing was spent this day" must never
 * look the same). A null [coveredRanges] means the caller asserts full coverage: empty days
 * are 0L.
 */
fun bucketDailySumCents(
    samples: List<Pair<Long, Long>>,
    startMs: Long,
    endMs: Long,
    coveredRanges: List<LongRange>? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Long?>
```

- Reuse `dailyBuckets` and `dayStartEpoch` exactly as `bucketDailyAverage` does. No new date math.
- A day is "covered" when its day-start epoch falls within any range (inclusive ends).
- Samples on a non-covered day still sum into that day IF present (data trumps the coverage
  claim: a real row proves the day existed - render the sum, not a gap).
- Sums are plain Long addition. No Float anywhere in this function.

## Part B: `DeckSmallMultiple` (`ui/common/DeckCharts.kt`)

The oil-analysis screen (ticket 06) renders a COLUMN of these; keep it primitive.

```kotlin
/**
 * One row of a small-multiples column: muted caps label left, bold mono latest-value right
 * (same contract as DeckRow: label truncates, value never), and a DeckSparkline beneath.
 * points passes straight through to DeckSparkline - null entries are gaps, per the file doc.
 */
@Composable
fun DeckSmallMultiple(label: String, latestValue: String, points: List<Float?>, modifier: Modifier = Modifier)
```

- Compose it from the existing pieces: the label/value line matches `DeckRow`'s type + colors
  (`LegionType.stamp` faint caps label, `LegionType.amount` value), then `DeckSparkline(points)`.
  Dashed top hairline like DeckRow. Do not duplicate DeckRow's internals - if DeckRow can host a
  content slot cheaply, reuse it; otherwise mirror its styles by reference.
- Add a `@Preview` with a gapped series, matching the file's existing preview style.

## Verification

- [ ] Unit tests in `DeckChartDataTest` for Part A: covered-empty day is 0L; uncovered day is
      null; sample on uncovered day still sums; null coveredRanges -> all 0L defaults; DST-window
      day count matches `dailyBuckets`; sums exact for values like 184212L.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] Preview compiles (rendering deferred to the map-level on-device check - previews have never
      been rendered in this repo).
