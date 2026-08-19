package com.kevin.legion.ui.common

import com.kevin.legion.ledger.formatCents
import com.kevin.legion.meals.dayStartEpoch
import java.time.Instant
import java.time.ZoneId

/**
 * The chart kit's pure layer (cyberdeck-ui ticket 14). No Compose import in
 * this file, on purpose - everything here is a plain function of already-
 * fetched data, unit-testable in [DeckChartDataTest] with no Robolectric and
 * no Canvas, matching the split [com.kevin.legion.ui.fleet.TelemetryRows.kt]
 * already draws between its "pure" and "rows" sections.
 *
 * **The kit's one load-bearing invariant, stated once and enforced everywhere
 * below: a period nothing was logged for is a GAP, never a zero.** CLAUDE.md
 * §4 rule six's "a check that passes on nothing parsed is not a gate" and
 * [com.kevin.legion.meals.DailyMealGap.NotLogged]'s D27 comment are the same
 * rule read onto ingestion and onto a gap page; this file is that rule read
 * onto a chart. Every bucketing helper below returns `null` for an empty day
 * rather than folding it into a `0f` - a caller that flattens a null into
 * `0.0` before it reaches [DeckSparkline]/[DeckLineChart]/[DeckBarChart] has
 * reintroduced the exact lie those three components exist to refuse to draw.
 *
 * **Money never touches [Double] here for a LABEL.** [centsLabel] delegates
 * straight to [com.kevin.legion.ledger.formatCents], which works in [Long]
 * cents end to end (CLAUDE.md §4 rule three). [Float] appears below only in
 * [ChartScale] and the [DeckPoint]/[DeckBar] value fields, which exist purely
 * to become pixel geometry on a [androidx.compose.foundation.Canvas] - a
 * chart's Y axis is allowed to be approximate, the number printed on the
 * BURN panel is not, and callers are expected to keep those two paths
 * separate the same way [com.kevin.legion.ui.fleet.TelemetryRows.formatReading]
 * and [TelemetryChart] already do for OBD readings.
 */

// ------------------------------------------------------------------- shapes

/**
 * One plotted sample for [DeckLineChart]/[DeckSparkline]: a timestamp (a
 * day-bucket start, when built by [bucketDailyAverage]) and its value. A
 * `null` entry in the containing `List<DeckPoint?>` - not a [DeckPoint] with
 * some sentinel value - is how an unlogged day reaches the chart; see the
 * file doc's invariant.
 *
 * [mark] is the optional shape-typed marker (mission-control ticket 15/06
 * answer #4) drawn at this point by [DeckLineChart] - `null` (the default)
 * draws nothing extra beyond the line itself, so every series built before
 * this ticket is unaffected. [DeckSparkline] takes a separate index-aligned
 * `markers: List<DeckMarkerType?>` parameter instead, because a sparkline's
 * points are a bare `List<Float?>` with no per-point object to carry a field.
 */
data class DeckPoint(val xMs: Long, val y: Float, val mark: DeckMarkerType? = null)

/**
 * One bar for [DeckBarChart]. [targetValue] is the optional amber dashed tick
 * (budget-vs-actual, plan-vs-actual - recoloured off the old green by mission-
 * control ticket 15/06: a target is a HIGHLIGHT, not a verdict, same
 * reasoning as [com.kevin.legion.ui.common.DeckMeter]'s pace tick);
 * [valueLabel] is pre-formatted text to print above/below this specific bar,
 * left `null` for bars the caller does not want labelled - "value labels
 * selective not on every bar" (ticket 14) is a call the chart's caller makes
 * per bar, not a rule the chart infers, because only the caller knows which
 * bars are the interesting ones (this week, the exception, the one over
 * budget). [mark] is the optional shape-typed marker (ticket 15/06 answer
 * #4) drawn just above this bar's own top edge - `null` (the default) draws
 * nothing extra, so every bar built before this ticket is unaffected.
 *
 * A `null` entry in the containing `List<DeckBar?>` is an absent slot (no day
 * logged), rendered as a muted underline marker rather than a zero-height
 * bar - see [DeckBarChart]'s doc comment for why a missing bar and a bar
 * genuinely worth zero must never look the same.
 */
data class DeckBar(
    val label: String,
    val value: Float,
    val targetValue: Float? = null,
    val valueLabel: String? = null,
    val mark: DeckMarkerType? = null,
)

/**
 * Shape vocabulary for a single plotted point or bar (mission-control ticket
 * 15, resolving ticket 06 answer #4). Hue can no longer carry per-point
 * meaning once green is retired - the palette is genuinely two-hue, mint for
 * every value and amber for every highlight - so PROVENANCE differs by drawn
 * SHAPE instead, all rendered in the one dedicated [com.kevin.legion.ui.theme.LegionSemantics.marker]
 * tint ([com.kevin.legion.ui.theme.DeckMarker], a deliberate near-amber nudge
 * off [com.kevin.legion.ui.theme.DeckAmber] and distinct from it by value
 * only - the four members below differ from each other by SHAPE, never by a
 * second hue). Every chart composable that accepts one defaults it to `null`
 * ("draw nothing extra, just the series line/bar"), so no call site built
 * before this ticket needed to change.
 */
enum class DeckMarkerType {
    /** A logged reading: filled dot. */
    LOGGED,
    /** The series' own latest value / endpoint: hollow dot (stroke only, unfilled). */
    ENDPOINT,
    /** An estimate - the source document never stated this figure (CLAUDE.md §4 rule 5): filled diamond. */
    ESTIMATE,
    /** Provisional / `IngestMethod.UNRECONCILED` (CLAUDE.md §4 rule 7): a cross. */
    PROVISIONAL,
}

/**
 * The one sanctioned exception to "small multiples by default" (mission-
 * control ticket 06 answer #2): a SECOND line overlaid on [DeckLineChart]'s
 * primary [DeckLineChart.series], allowed only where the comparison itself is
 * the point (actual vs budget, actual vs target). Always amber, always
 * direct-labelled at its own endpoint - never a legend - mirroring
 * [DeckBar.targetValue]'s in-place labelling instead of pulling the label off
 * to a side panel.
 *
 * **The two-series cap is structural, not a runtime check.** [DeckLineChart]
 * has exactly one primary-series parameter and exactly one optional overlay
 * parameter of this type - there is no `List<DeckLineOverlay>` anywhere on
 * that composable for a caller to accidentally grow past two, unlike (say) a
 * generic multi-series API would allow. A caller cannot reach a third
 * overlaid series without editing this file.
 */
data class DeckLineOverlay(
    val series: List<DeckPoint?>,
    val label: String,
)

/**
 * The drilldown range selector's four stops (ticket 07/08 answers: "one
 * range selector inside drilldowns only (7d / 30d / 90d / all)"). [spanDays]
 * null means "everything recorded" - the same "0 is exactly no lower bound"
 * posture [com.kevin.legion.ui.fleet.TelemetryRows.rangeStartMs] already uses
 * for [com.kevin.legion.ui.fleet.TelemetryRange.ALL].
 */
enum class DeckRange(val label: String, val spanDays: Int?) {
    SEVEN_DAY("7D", 7),
    THIRTY_DAY("30D", 30),
    NINETY_DAY("90D", 90),
    ALL("ALL", null),
}

/**
 * The inclusive start of [range], counting back from [nowMs]'s LOCAL
 * calendar day in [zone] - a 7D range that includes "today" spans today plus
 * the six days before it, i.e. seven day-buckets, not eight.
 *
 * Walked with [java.time.LocalDate.minusDays] rather than a flat
 * `spanDays * 86_400_000L` subtraction in millis, because a DST-shift day is
 * 23 or 25 hours long in [zone] and the millis shortcut would land the
 * boundary on the wrong local date on exactly that day - the same reasoning
 * [com.kevin.legion.meals.dayEndEpoch] documents for why it advances the
 * calendar date instead of adding 24 hours.
 *
 * [DeckRange.ALL] returns 0L: the query underneath is a `>=` bound, so 0 is
 * exactly "no lower bound" and this function needs no knowledge of the
 * oldest row on file.
 */
fun deckRangeStartMs(range: DeckRange, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val span = range.spanDays ?: return 0L
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return today.minusDays((span - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
}

// ---------------------------------------------------------------- bucketing

/**
 * Every local day-start timestamp from [startMs]'s calendar day through
 * [endMs]'s calendar day, inclusive of both ends, in [zone]. Walked by
 * [java.time.LocalDate.plusDays] for the same DST-boundary reason
 * [deckRangeStartMs] documents.
 *
 * Returns an empty list rather than throwing when [endMs] falls before
 * [startMs] (a degenerate/misordered caller range) - callers downstream
 * (empty-series guards in [DeckSparkline]/[DeckLineChart]/[DeckBarChart])
 * already treat an empty list as "nothing to draw", so this is the one place
 * that failure mode needs handling.
 */
fun dailyBuckets(startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val startDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
    if (endDay.isBefore(startDay)) return emptyList()
    val out = mutableListOf<Long>()
    var cursor = startDay
    while (!cursor.isAfter(endDay)) {
        out.add(cursor.atStartOfDay(zone).toInstant().toEpochMilli())
        cursor = cursor.plusDays(1)
    }
    return out
}

/**
 * Folds raw `(timestampMs, value)` samples into one [DeckPoint] per local day
 * in `[startMs, endMs]` ([zone], default the device's own - "device zone
 * default, consistent with the repo's 2026-08-07 timezone fix" per ticket
 * 14's brief, the same fix [com.kevin.legion.util.documentDate]'s doc comment
 * and [com.kevin.legion.meals.dayStartEpoch] both record). A day with one or
 * more samples gets the AVERAGE of those samples' values; a day with none
 * gets `null` - **never** `DeckPoint(day, 0f)` - per the file doc's
 * invariant. [com.kevin.legion.meals.dayStartEpoch] is reused rather than
 * reimplemented, exactly as ticket 14's brief calls for, so a future zone fix
 * only has one function to correct.
 */
fun bucketDailyAverage(
    samples: List<Pair<Long, Float>>,
    startMs: Long,
    endMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DeckPoint?> {
    val days = dailyBuckets(startMs, endMs, zone)
    if (days.isEmpty()) return emptyList()
    val grouped = samples.groupBy { dayStartEpoch(it.first, zone) }
    return days.map { dayStart ->
        val bucket = grouped[dayStart]
        if (bucket.isNullOrEmpty()) {
            null
        } else {
            DeckPoint(xMs = dayStart, y = (bucket.sumOf { it.second.toDouble() } / bucket.size).toFloat())
        }
    }
}

/**
 * Folds raw `(timestampMs, amountCents)` samples into one Long-cents SUM per
 * local day in `[startMs, endMs]` (map taste call 3, quant-viz ticket 01) -
 * the money-side counterpart to [bucketDailyAverage], which is right for
 * READINGS (weight, voltage) but wrong for spend, where the day's figure is
 * everything logged that day added up, not averaged down toward one sample.
 *
 * Three-way per day, not [bucketDailyAverage]'s two:
 * - **A day inside at least one of [coveredRanges] with no samples is `0L`
 *   - a genuine zero.** A statement covered that day and nothing was spent.
 * - **A day outside every covered range is `null` - a GAP.** No statement
 *   covers that day, so the app cannot claim to know it was zero; this is the
 *   file doc's invariant read onto money exactly as ticket 01 states it:
 *   "no statement covered this day" and "nothing was spent this day" must
 *   never look the same bar.
 * - **A sample on an uncovered day still sums into that day.** Data trumps
 *   the coverage claim - a real row proves the day existed regardless of
 *   what [coveredRanges] says, so it renders the sum, never a gap.
 *
 * [coveredRanges] `null` means the caller asserts full coverage over
 * `[startMs, endMs]` (no statement-window bookkeeping to consult): every day
 * with no samples defaults to `0L`, matching [bucketDailyAverage]'s posture
 * when a caller has no coverage concept at all.
 *
 * Reuses [dailyBuckets] and [com.kevin.legion.meals.dayStartEpoch] exactly as
 * [bucketDailyAverage] does - no new date math, so a future zone fix still
 * has only one function to correct. Sums are plain [Long] addition; no
 * [Float] anywhere in this function (CLAUDE.md §4 rule three).
 */
fun bucketDailySumCents(
    samples: List<Pair<Long, Long>>,
    startMs: Long,
    endMs: Long,
    coveredRanges: List<LongRange>? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Long?> {
    val days = dailyBuckets(startMs, endMs, zone)
    if (days.isEmpty()) return emptyList()
    val grouped = samples.groupBy { dayStartEpoch(it.first, zone) }
    return days.map { dayStart ->
        val bucket = grouped[dayStart]
        if (!bucket.isNullOrEmpty()) {
            // A real row proves the day existed - sum it regardless of
            // whether coveredRanges agrees the day was covered.
            bucket.sumOf { it.second }
        } else if (coveredRanges == null || coveredRanges.any { dayStart in it }) {
            0L
        } else {
            null
        }
    }
}

// -------------------------------------------------------------------- scale

/**
 * A chart's Y axis: `[min, max]` in the value's own units, already padded so
 * the drawn line/bars never touch the panel's top or bottom edge.
 */
data class ChartScale(val min: Float, val max: Float) {
    /** Never negative and never zero when [min] and [max] differ - see the two constructors below for the only way [min] == [max] survives here. */
    val span: Float get() = (max - min).coerceAtLeast(0f)
}

/**
 * A line chart's Y scale (padded top and bottom) from whatever finite
 * `y` values are present - `null` entries (gaps) contribute nothing, exactly
 * like [com.kevin.legion.ui.fleet.TelemetryChart]'s `ys.min()`/`ys.max()`
 * only ever sees points that exist.
 *
 * Three degenerate cases, all guarded so nothing downstream divides by zero:
 * - **No values at all** (every entry a gap, or an empty series): an
 *   arbitrary `0f..1f` - the caller already has nothing to draw and this
 *   value is never read for anything but avoiding a NaN.
 * - **Exactly one value, or every value identical** (a flat series - a
 *   reading that never moved, same as [com.kevin.legion.ui.fleet.TelemetryChart]'s
 *   "battery that never moved off 12.4 V" case): pads by a nominal amount
 *   around the single value rather than by [paddingFraction] of a zero span,
 *   which would pad by exactly nothing. Padding by `1f` when the value
 *   itself is `0f` for the same reason - a fraction of zero is zero.
 * - **A genuine range**: min/max padded outward by [paddingFraction] of the
 *   span on each side, so the drawn line has headroom.
 */
fun computeLineScale(values: List<Float>, paddingFraction: Float = 0.1f): ChartScale {
    if (values.isEmpty()) return ChartScale(0f, 1f)
    val rawMin = values.min()
    val rawMax = values.max()
    if (rawMin == rawMax) {
        val pad = if (rawMin == 0f) 1f else kotlin.math.abs(rawMin) * paddingFraction
        return ChartScale(rawMin - pad, rawMax + pad)
    }
    val span = rawMax - rawMin
    val pad = span * paddingFraction
    return ChartScale(rawMin - pad, rawMax + pad)
}

/**
 * A bar chart's Y scale. Baseline is forced to `0f` (bars grow from a
 * floor, unlike [computeLineScale]'s free-floating band) and the ceiling is
 * the padded max of every bar's [DeckBar.value] AND [DeckBar.targetValue] -
 * a target tick that sits above every bar must still fit on the panel, or
 * the "vs target" comparison the tick exists to show becomes invisible.
 *
 * Absent slots (`null` entries in [bars]) and bars with no target contribute
 * nothing. An all-empty or all-zero [bars] list (nothing logged this window,
 * or every bar genuinely reads zero) returns `ChartScale(0f, 1f)` rather than
 * `ChartScale(0f, 0f)`, so a caller can still draw an empty baseline without
 * a zero-height Canvas.
 */
fun computeBarScale(bars: List<DeckBar?>, paddingFraction: Float = 0.15f): ChartScale {
    val present = bars.filterNotNull()
    val candidates = present.map { it.value } + present.mapNotNull { it.targetValue }
    val rawMax = candidates.maxOrNull() ?: 0f
    if (rawMax <= 0f) return ChartScale(0f, 1f)
    return ChartScale(0f, rawMax * (1f + paddingFraction))
}

// --------------------------------------------------------------------- money

/**
 * A chart-facing money label, delegating straight to
 * [com.kevin.legion.ledger.formatCents] - [Long] cents in, formatted string
 * out, no [Double]/[Float] in between. This exists as its own function (not
 * just "call formatCents at the call site") so [DeckChartDataTest] has one
 * seam to pin the exact-value behaviour this kit's ticket calls for
 * ("money labels exact for values like 184212L cents"), independent of
 * whichever screen ticket ends up wiring a real BURN/FLOW panel to it.
 */
fun centsLabel(cents: Long): String = formatCents(cents)

/**
 * A bar's own on-chart value label (Kevin, 2026-08-16: "I need the data label... on top of all
 * the bars"), used by both [com.kevin.legion.ui.ledger.categorySpendBars] (the CRED spend-hero
 * chart, up to 6 columns) and [com.kevin.legion.ui.ledger.monthlySpendBars] (the spend-by-month
 * drilldown, up to 12). [centsLabel]'s `"1,842.12"` is the right precision for a list row read at
 * leisure; it is the wrong precision for a label stamped ABOVE a ~28-30dp-wide bar column - twelve
 * of those at 384dp phone width overlap their neighbours immediately (see the ticket's own build
 * report for the arithmetic). This is the label built for that column width instead: whole
 * dollars, no cents, and abbreviated once the number itself gets wide enough to threaten the
 * column - Kevin reads the current single-bar label as "238", so whole dollars is what he is
 * already reading, not a new register.
 *
 * **Three bands, all integer-only ([Long] the whole way through - CLAUDE.md §4 rule three's "money
 * is Long cents, never Double" extends here even though the OUTPUT is a display string, not a
 * stored figure, because the ROUNDING itself must be exact and reproducible, not float-drift-prone):**
 * - **Under $1,000**: the plain whole-dollar figure, e.g. `238.06 -> "238"`. Three digits, no
 *   thousands separator needed - a column this narrow has no room for one anyway.
 * - **$1,000 up to $9,999.xx**: one decimal of thousands, e.g. `3_500.00 -> "3.5k"` - a bare `"4k"`
 *   would round two adjacent categories (`$3,500` and `$4,400`) onto the same label, which is a
 *   worse lie for a chart whose whole job is comparing bar heights than one extra character costs.
 *   The trailing `.0` is dropped when rounding lands exactly on a multiple of $1,000 (`3_000.00 ->
 *   "3k"`, not `"3.0k"`) - a decimal that carries no information is chart noise, not precision.
 * - **$10,000 and up**: whole thousands only, e.g. `12_400.00 -> "12k"` - a fifth character
 *   (`"12.4k"`) is exactly the width this function exists to refuse at a 12-column month chart;
 *   personal ledger totals this large are also rare enough that the extra decimal buys little.
 *
 * **Rounding is round-half-up**, done as pure integer arithmetic (`(cents + 50) / 100` for the
 * whole-dollar step, the same "add half a unit, then truncate" trick one level up for the
 * thousands step) rather than [Math.round] on a [Double] - `23850L` (`$238.50`) must round to
 * `239`, not silently do something else on a platform whose float rounding mode differs.
 * `internal`, not `private`, so [DeckChartDataTest] can pin every band and both boundaries without
 * Compose.
 */
internal fun deckWholeDollarLabel(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val absCents = kotlin.math.abs(cents)
    // Round to the nearest whole dollar first - every band below reads off this, never off cents.
    val dollars = (absCents + 50L) / 100L
    if (dollars < 1_000L) return "$sign$dollars"
    if (dollars < 10_000L) {
        // One decimal of thousands: e.g. dollars=3500 -> tenths=35 -> "3.5k".
        val tenths = (dollars + 50L) / 100L
        val whole = tenths / 10L
        val frac = tenths % 10L
        return if (frac == 0L) "$sign${whole}k" else "$sign$whole.${frac}k"
    }
    // Whole thousands only: e.g. dollars=12400 -> 12 -> "12k".
    val wholeK = (dollars + 500L) / 1_000L
    return "$sign${wholeK}k"
}
