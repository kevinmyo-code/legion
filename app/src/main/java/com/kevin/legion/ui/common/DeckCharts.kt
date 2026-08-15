package com.kevin.legion.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.DRAW_IN_MS
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled

/**
 * The deck's hand-rolled Canvas chart kit (cyberdeck-ui ticket 14, resolved by
 * ticket 02's research: no chart dependency, `TelemetryChart` in
 * `ui/fleet/TelemetryRows.kt` is the pattern to extend). Every consumer
 * ticket (07 body, 08 ledger, and any later BIO-grammar module) reads these
 * three shapes plus [DeckRangeSelector] rather than hand-rolling its own
 * Canvas, matching [DeckPanels.kt]'s "built once" posture for the panel
 * primitives.
 *
 * **This ticket builds no screen.** Nothing here is wired to a real data
 * source; [DeckChartData.kt] is the pure layer these read, and its file doc
 * states the kit's one invariant: *a period nothing was logged for is a GAP,
 * never a zero*. Every component below honours that by construction - a
 * `null` entry in a `List<T?>` never reaches the drawn line/bar as `0`.
 *
 * **Motion**: [DeckLineChart] and [DeckBarChart] each draw in once over
 * [DRAW_IN_MS] on entry (ticket 04 answer #2) and collapse to their final
 * state instantly when [deckMotionEnabled] is false (ticket 04 answer #5).
 * [DeckSparkline] does not animate - it sits inline in a panel that is
 * already mid-recomposition-heavy real estate (four sparklines on BODY's
 * screen alone), and ticket 04's ration is explicit that draw-ins are for
 * "charts" on screen entry, not for every inline trend glyph.
 *
 * **Recoloured under the two-hue VACUUM/SENTRY palette (mission-control
 * ticket 15, resolving ticket 06's grilling).** Every series/fill that used
 * to read [MaterialTheme.colorScheme.primary] (amber) now reads
 * [LocalLegionSemantics.current.data] (mint) - a chart's plotted line/bar IS
 * a value, same as [DeckRow]'s value text or [DeckMeter]'s fill, both already
 * moved to mint by ticket 13/03. Threshold and target lines move the OTHER
 * direction, off the old (already-broken, see [DeckBarChart]'s doc) green
 * [LegionSemantics.credit] onto [MaterialTheme.colorScheme.primary] (amber),
 * dashed - a target is a highlight, not a value, per [DeckMeter]'s own pace-
 * tick reasoning. **Chrome (gridlines, axis labels) is UNCHANGED** - ticket
 * 06 answer #3 is a deliberate, stated scoped exception: red never enters a
 * plot except as a genuine ALARM annotation (ticket 04 territory, not built
 * here).
 *
 * **Shape-typed markers** ([DeckMarkerType], ticket 06 answer #4) are new:
 * every composable below that plots a point or a bar takes an optional,
 * defaulted-to-`null` marker parameter, drawn by the shared
 * [drawDeckMarker] in the one dedicated [LegionSemantics.marker] tint - hue
 * never carries per-point meaning, shape does. **Multi-series stays small-
 * multiples by default**; [DeckLineChart] gains the one sanctioned exception
 * ([DeckLineOverlay], ticket 06 answer #2) - a second, always-amber,
 * always-endpoint-labelled line, capped at two series STRUCTURALLY (one
 * typed parameter, not a list a caller could grow past two).
 */

/**
 * Draws one shape-typed marker (mission-control ticket 15, resolving ticket
 * 06 answer #4) at [center] in [color], sized to [radius] - pulled out to one
 * shared function so the four shapes stay pixel-identical everywhere they
 * appear ([DeckSparkline], [DeckLineChart], [DeckBarChart]) rather than each
 * composable hand-rolling its own diamond/cross geometry. [color] is always
 * expected to be [LegionSemantics.marker] at every call site below - kept as
 * a parameter rather than reading the CompositionLocal here so this stays a
 * bare [DrawScope] extension, callable from inside any of the three
 * `Canvas{}` blocks without its own `@Composable` context.
 */
private fun DrawScope.drawDeckMarker(type: DeckMarkerType, center: Offset, color: Color, radius: Float) {
    when (type) {
        // A logged reading: filled dot, the plainest mark.
        DeckMarkerType.LOGGED -> drawCircle(color = color, radius = radius, center = center)
        // The latest value / endpoint: hollow (stroke-only) dot - distinct from
        // LOGGED by outline alone, same "state reads as a shape too" posture
        // DeckPanels.kt's inverted DeckTag styles already use.
        DeckMarkerType.ENDPOINT -> drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = radius * 0.6f),
        )
        // An estimate (CLAUDE.md §4 rule 5): filled diamond.
        DeckMarkerType.ESTIMATE -> {
            val diamond = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(diamond, color = color)
        }
        // Provisional / UNRECONCILED (CLAUDE.md §4 rule 7): a cross, the shape
        // most readers already associate with "not verified".
        DeckMarkerType.PROVISIONAL -> {
            val half = radius * 0.85f
            val strokePx = radius * 0.55f
            drawLine(color, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y + half), strokeWidth = strokePx)
            drawLine(color, Offset(center.x - half, center.y + half), Offset(center.x + half, center.y - half), strokeWidth = strokePx)
        }
    }
}

// ---------------------------------------------------------------- sparkline

/**
 * A panel-height (~54dp) trend line with no axes and no labels - the shape of
 * the data, not its numbers, exactly like [com.kevin.legion.ui.fleet.TelemetryChart]'s
 * doc comment reasons: the exact figures belong in the panel's hero readout
 * text, not redrawn a second time as tick marks.
 *
 * [points] is index-ordered (evenly spaced left to right, no timestamps -
 * a sparkline's whole point is silhouette, not a time axis) and a `null`
 * entry is a GAP: the pen lifts, no line is drawn across it, and it resumes
 * on the next non-null point. A run of exactly one non-null point between
 * two gaps (or a series of length one) draws only as the endpoint dot, never
 * as an invisible zero-length line. The series' own min/max come only from
 * its non-null values ([computeLineScale]), so gaps never drag the scale
 * toward zero.
 *
 * A flat series (every value equal) draws as a level line rather than
 * dividing by a zero range - [computeLineScale]'s degenerate-case padding is
 * what makes that safe, same defensive shape as
 * [com.kevin.legion.ui.fleet.TelemetryChart]'s "battery that never moved off
 * 12.4 V" case.
 *
 * [markers] is an OPTIONAL index-aligned twin of [points] (mission-control
 * ticket 15, resolving ticket 06 answer #4) - `markers[i]` types the mark
 * drawn at `points[i]`, or draws nothing if `null`/absent/shorter than
 * `points`. **Leaving it `null` (the default) is not just "no marks" - it
 * keeps the exact legacy behaviour** every sparkline built before this
 * ticket already has: one plain filled dot, in the series colour, at the
 * last plotted point. Passing a non-null list REPLACES that legacy dot
 * entirely with whatever the caller marked (a caller that still wants a dot
 * at the end marks that index [DeckMarkerType.ENDPOINT]) - the two modes are
 * deliberately exclusive so a caller who opts in is never fighting an
 * uncontrollable extra dot drawn underneath their own marks.
 */
@Composable
fun DeckSparkline(
    points: List<Float?>,
    modifier: Modifier = Modifier,
    markers: List<DeckMarkerType?>? = null,
) {
    val sem = LocalLegionSemantics.current
    val lineColor = sem.data
    val markerColor = sem.marker
    Canvas(modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp, vertical = 4.dp)) {
        val nonNull = points.filterNotNull()
        if (nonNull.isEmpty()) return@Canvas
        val scale = computeLineScale(nonNull)
        val n = points.size
        val strokePx = 2.dp.toPx()
        val dotRadius = 3.dp.toPx()

        fun xFor(index: Int): Float = if (n <= 1) size.width / 2f else size.width * index / (n - 1).toFloat()
        fun yFor(value: Float): Float = size.height - ((value - scale.min) / scale.span) * size.height

        // Pen-up/pen-down: a new Path starts every time a gap breaks a run,
        // so a `null` never becomes a straight line jumping across it. One
        // pass does both jobs - stroking each finished segment as soon as a
        // gap (or the series' end) closes it, and remembering the last
        // plotted index for the legacy endpoint dot below.
        var lastNonNullIndex = -1
        var segment: Path? = null
        for (i in points.indices) {
            val value = points[i]
            if (value == null) {
                segment?.let { drawPath(it, color = lineColor, style = Stroke(width = strokePx)) }
                segment = null
                continue
            }
            val point = Offset(xFor(i), yFor(value))
            val running = segment
            if (running == null) {
                segment = Path().apply { moveTo(point.x, point.y) }
            } else {
                running.lineTo(point.x, point.y)
            }
            lastNonNullIndex = i
        }
        segment?.let { drawPath(it, color = lineColor, style = Stroke(width = strokePx)) }

        if (markers == null) {
            if (lastNonNullIndex >= 0) {
                val endpoint = Offset(xFor(lastNonNullIndex), yFor(points[lastNonNullIndex]!!))
                drawCircle(color = lineColor, radius = dotRadius, center = endpoint)
            }
        } else {
            for (i in points.indices) {
                val value = points[i] ?: continue
                val mark = markers.getOrNull(i) ?: continue
                drawDeckMarker(mark, Offset(xFor(i), yFor(value)), markerColor, dotRadius)
            }
        }
    }
}

// ---------------------------------------------------------- small multiple

/**
 * One row of a small-multiples column (quant-viz ticket 01; the oil-analysis
 * screen, ticket 06, stacks a COLUMN of these - one per tracked measure).
 * Mirrors [DeckRow]'s dashed top hairline and label/value styling
 * ([label] muted caps, truncating; [latestValue] bold amber mono, never
 * truncating - same "a value getting clipped is worse than a label running
 * long" posture [DeckRow]'s doc comment states) rather than reusing [DeckRow]
 * itself, because [DeckRow] is a single [Row] with no slot for a chart
 * beneath it; duplicating its two styles by reference here is cheaper than
 * reshaping a workhorse used everywhere else in MILSPEC to host one.
 *
 * [points] passes straight through to [DeckSparkline] - a `null` entry is a
 * GAP (see the file doc's invariant), not a caller's job to flatten before it
 * arrives here. [markers] passes straight through too (ticket 15), same
 * index-alignment and same "`null` keeps the legacy endpoint dot" contract
 * [DeckSparkline]'s own doc states.
 */
@Composable
fun DeckSmallMultiple(
    label: String,
    latestValue: String,
    points: List<Float?>,
    modifier: Modifier = Modifier,
    markers: List<DeckMarkerType?>? = null,
) {
    val sem = LocalLegionSemantics.current
    val dashStroke = with(LocalDensity.current) { 1.dp.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = sem.ruleFaint,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = dashStroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                )
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label.uppercase(),
                style = LegionType.stamp,
                color = sem.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                latestValue,
                style = LegionType.amount,
                color = sem.data,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
        DeckSparkline(points, markers = markers)
    }
}

// ---------------------------------------------------------------- line chart

/**
 * The drilldown-height (~180dp) full chart: a faint line-colour grid, mono
 * axis labels via [rememberTextMeasurer]/[drawText] (no `Text` composables -
 * a `Canvas` cannot host them, and measuring once per draw keeps this off
 * the recomposition-heavy path the same way [DeckPanels.StatusLine]'s cursor
 * read is kept off it), and a one-shot draw-in over [DRAW_IN_MS].
 *
 * [series] is one [DeckPoint]`?` per x-slot, `null` a GAP (see file doc).
 * [yLabel] renders the numeric value at each grid rule - callers pass
 * [centsLabel] for a money axis, or a plain formatter for anything else;
 * this function never formats a number itself, so the exactness rule
 * ("Long cents never touch Double for a label") lives entirely in the
 * caller-supplied formatter, not here. [xLabels] is index-aligned with
 * [series] (same size expected) and a blank string at an index means "no
 * label under this slot" - callers thin their own labels (every 7th day,
 * month boundaries) rather than this component guessing a decimation.
 *
 * The draw-in ([drawFraction]) clips the plotted path horizontally with
 * [clipRect] rather than animating point positions - a left-to-right reveal
 * reads as a "scan" (in keeping with the deck's avionics register) and costs
 * one clip instead of re-computing geometry every frame.
 * [deckMotionEnabled] false collapses [drawFraction] to `1f` on the very
 * first frame via [snap] (ticket 04 answer #5).
 *
 * [seriesLabel], if non-null, direct-labels the primary series at its OWN
 * endpoint (ticket 15/06 answer #2's "direct-labelled at their endpoints",
 * mint text beside a hollow [DeckMarkerType.ENDPOINT] dot) rather than a
 * legend - left `null` (the default) draws no label, which is correct for
 * every single-series call site, where there is nothing to disambiguate.
 * [DeckPoint.mark] (per-point, e.g. an [DeckMarkerType.ESTIMATE] on a
 * projected day) draws inline with the primary series only - [overlay]'s own
 * series never carries per-point marks, since it exists purely as the
 * "compare against" line, not a second set of provenance to call out.
 *
 * [overlay] is [DeckLineChart]'s one sanctioned exception to "small
 * multiples by default" (ticket 06 answer #2) - a SECOND series, always
 * amber, always direct-labelled at its own endpoint the same way
 * [seriesLabel] labels the primary one. **The two-series cap is structural**:
 * there is exactly one `series` parameter and exactly one optional
 * `overlay` parameter, so a caller cannot reach three without editing this
 * function - see [DeckLineOverlay]'s own doc. The one runtime check this
 * function does add is alignment, not count: [overlay]'s series must be the
 * same length as [series] (both are read by the same `xFor`/index math
 * below), so a mismatched pair is a compile-time-knowable caller mistake
 * caught with `require()` rather than an out-of-bounds read.
 */
@Composable
fun DeckLineChart(
    series: List<DeckPoint?>,
    yLabel: (Float) -> String,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    seriesLabel: String? = null,
    overlay: DeckLineOverlay? = null,
) {
    if (overlay != null) {
        require(overlay.series.size == series.size) {
            "DeckLineChart overlay must be index-aligned with the primary series: " +
                "primary has ${series.size} slots, overlay ('${overlay.label}') has ${overlay.series.size}."
        }
    }
    val sem = LocalLegionSemantics.current
    val lineColor = sem.data
    val overlayColor = MaterialTheme.colorScheme.primary
    val markerColor = sem.marker
    val motionEnabled = deckMotionEnabled()
    val textMeasurer = rememberTextMeasurer()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(series) { revealed = true }
    val drawFraction by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (motionEnabled) tween(DRAW_IN_MS) else snap(),
        label = "deck-line-chart-draw-in",
    )
    val axisStyle = LegionType.stamp.copy(color = sem.faint)

    Canvas(modifier.fillMaxWidth().height(180.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val nonNullY = series.filterNotNull().map { it.y } + (overlay?.series?.filterNotNull()?.map { it.y } ?: emptyList())
        if (nonNullY.isEmpty()) return@Canvas
        // Both lines share ONE scale (computed across BOTH series, when an
        // overlay is present) - a target/actual comparison is meaningless if
        // the two lines are secretly on different y-axes.
        val scale = computeLineScale(nonNullY)
        val gridLineCount = 4
        val gridStrokePx = 1.dp.toPx()

        // The grid: gridLineCount horizontal rules from scale.min (bottom) to
        // scale.max (top), each carrying its own value via yLabel - "faint
        // line-colour grid... mono axis labels" per ticket 14. UNCHANGED by
        // ticket 15 (ticket 06 answer #3: chrome stays out of the plot).
        for (g in 0 until gridLineCount) {
            val frac = g / (gridLineCount - 1).toFloat()
            val y = size.height * (1f - frac)
            drawLine(sem.ruleFaint, Offset(0f, y), Offset(size.width, y), gridStrokePx)
            val value = scale.min + scale.span * frac
            val layout = textMeasurer.measure(AnnotatedString(yLabel(value)), style = axisStyle)
            val labelY = (y - layout.size.height).coerceAtLeast(0f)
            drawText(layout, topLeft = Offset(2.dp.toPx(), labelY))
        }

        val n = series.size
        fun xFor(index: Int): Float = if (n <= 1) size.width / 2f else size.width * index / (n - 1).toFloat()
        fun yFor(value: Float): Float = size.height - ((value - scale.min) / scale.span) * size.height

        // Plots one series (pen-up/pen-down on every gap, exactly like the
        // pre-ticket-15 single-series body below), optionally drawing each
        // point's own [DeckPoint.mark], and returns the last plotted offset -
        // `null` if every point was a gap - so the endpoint label below has
        // somewhere to sit.
        fun DrawScope.plotSeries(points: List<DeckPoint?>, color: Color, drawMarks: Boolean): Offset? {
            var segment: Path? = null
            var lastOffset: Offset? = null
            for (i in points.indices) {
                val point = points[i]
                if (point == null) {
                    segment?.let { drawPath(it, color = color, style = Stroke(width = 2.dp.toPx())) }
                    segment = null
                    continue
                }
                val offset = Offset(xFor(i), yFor(point.y))
                val running = segment
                if (running == null) {
                    segment = Path().apply { moveTo(offset.x, offset.y) }
                } else {
                    running.lineTo(offset.x, offset.y)
                }
                if (drawMarks) point.mark?.let { drawDeckMarker(it, offset, markerColor, 3.dp.toPx()) }
                lastOffset = offset
            }
            segment?.let { drawPath(it, color = color, style = Stroke(width = 2.dp.toPx())) }
            return lastOffset
        }

        clipRect(left = 0f, top = 0f, right = size.width * drawFraction, bottom = size.height) {
            val primaryEnd = plotSeries(series, lineColor, drawMarks = true)
            val overlayEnd = overlay?.let { plotSeries(it.series, overlayColor, drawMarks = false) }

            // Direct-labelled endpoints (ticket 06 answer #2), drawn inside the
            // SAME clip as the lines they belong to - a label appearing ahead of
            // its own line mid-scan would read as a glitch, not a reveal.
            if (seriesLabel != null && primaryEnd != null) {
                drawEndpointLabel(textMeasurer, seriesLabel, primaryEnd, markerColor, lineColor, axisStyle)
            }
            if (overlay != null && overlayEnd != null) {
                drawEndpointLabel(textMeasurer, overlay.label, overlayEnd, markerColor, overlayColor, axisStyle)
            }
        }

        for (i in xLabels.indices) {
            if (i >= n) break
            val text = xLabels[i]
            if (text.isBlank()) continue
            val layout = textMeasurer.measure(AnnotatedString(text), style = axisStyle)
            val x = (xFor(i) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, size.height - layout.size.height))
        }
    }
}

/**
 * Draws one [DeckLineChart] endpoint label: a hollow [DeckMarkerType.ENDPOINT]
 * dot in [markerColor] (the shared marker tint, never a per-series hue - see
 * the file doc) plus [text] in [textColor] (the OWNING series' own colour -
 * mint for the primary line, amber for an [DeckLineOverlay] - so a reader
 * knows which line a label belongs to by matching text colour to line
 * colour, the same "colour carries which value, not which mark" split
 * [drawDeckMarker] draws elsewhere). Coerced to stay inside the canvas so a
 * label near the right edge never clips off it.
 */
private fun DrawScope.drawEndpointLabel(
    textMeasurer: TextMeasurer,
    text: String,
    at: Offset,
    markerColor: Color,
    textColor: Color,
    style: TextStyle,
) {
    drawDeckMarker(DeckMarkerType.ENDPOINT, at, markerColor, 3.dp.toPx())
    val layout = textMeasurer.measure(AnnotatedString(text), style = style.copy(color = textColor))
    val labelX = (at.x + 6.dp.toPx()).coerceAtMost((size.width - layout.size.width).coerceAtLeast(0f))
    val labelY = (at.y - layout.size.height / 2f).coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(labelX, labelY))
}

// ----------------------------------------------------------------- bar chart

/**
 * The drilldown-height (~180dp) bar comparison: mint fills, 2dp gaps between
 * bars, an optional per-bar amber DASHED target tick (budget-vs-actual,
 * plan-vs-actual), and selective value labels (only bars whose
 * [DeckBar.valueLabel] is non-null get one - see that field's doc for why
 * the selection is the caller's call, not this component's).
 *
 * **Recoloured, and a live bug fixed with it (mission-control ticket 15,
 * resolving ticket 06).** The fill used to be [MaterialTheme.colorScheme.primary]
 * (amber) against a [LegionSemantics.credit] target line - back when
 * `credit` was green, ticket 06's `dataviz` validator measured that exact
 * amber-fill/green-target pairing at dE 5.5 under deuteranopia, an accessibility
 * failure shipped before this ticket, not introduced by it. Green is gone
 * from the palette entirely now (see [com.kevin.legion.ui.theme.Color.kt]'s
 * "GREEN IS REMOVED" note), so the fill moves to [LegionSemantics.data]
 * (mint, a value) and the target line moves to
 * [MaterialTheme.colorScheme.primary] (amber, DASHED - a highlight, not a
 * verdict, the same split [DeckMeter]'s fill/pace-tick already draws).
 *
 * **A `null` entry in [bars] is an absent slot, never a zero-height bar.**
 * This is the file doc's invariant made visually literal: a day nothing was
 * logged for draws as a short muted underline at the baseline (present
 * enough to hold the slot's place in the row of columns, absent enough that
 * it is never mistaken at a glance for "logged, and it was zero" - the
 * distinction CLAUDE.md §4 rule six exists to keep a chart from erasing).
 *
 * [DeckBar.mark], when present, draws just above that bar's own top edge in
 * the shared marker tint (ticket 06 answer #4) - optional and defaulted to
 * `null`, so every bar built before this ticket is unaffected.
 *
 * The bar heights (not a clip, unlike [DeckLineChart]) scale by the same
 * one-shot [drawFraction] on entry, so the whole row visibly "grows" from the
 * baseline - the more natural draw-in shape for bars, matching
 * [DeckPanels.DeckMeter]'s fill-in treatment of a single bar.
 */
@Composable
fun DeckBarChart(bars: List<DeckBar?>, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val sem = LocalLegionSemantics.current
    val fillColor = sem.data
    val targetColor = MaterialTheme.colorScheme.primary
    val markerColor = sem.marker
    val targetDash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
    val motionEnabled = deckMotionEnabled()
    val textMeasurer = rememberTextMeasurer()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(bars) { revealed = true }
    val drawFraction by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (motionEnabled) tween(DRAW_IN_MS) else snap(),
        label = "deck-bar-chart-draw-in",
    )
    val labelStyle = LegionType.stamp.copy(color = sem.faint)
    val scale = computeBarScale(bars)

    Canvas(modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (bars.isEmpty()) return@Canvas
        val gapPx = 2.dp.toPx()
        val n = bars.size
        val totalGap = gapPx * (n - 1).coerceAtLeast(0)
        val barWidth = ((size.width - totalGap) / n).coerceAtLeast(1f)
        val ceiling = scale.max.coerceAtLeast(1e-6f)

        for (i in bars.indices) {
            val x = i * (barWidth + gapPx)
            val bar = bars[i]
            if (bar == null) {
                // Absent slot: a short muted underline, not a bar of any height.
                val markerWidth = barWidth * 0.4f
                val markerX = x + (barWidth - markerWidth) / 2f
                val markerY = size.height - 1.dp.toPx()
                drawLine(
                    color = sem.faint,
                    start = Offset(markerX, markerY),
                    end = Offset(markerX + markerWidth, markerY),
                    strokeWidth = 2.dp.toPx(),
                )
                continue
            }
            val fullHeight = (bar.value / ceiling).coerceIn(0f, 1f) * size.height
            val height = fullHeight * drawFraction
            drawRect(
                color = fillColor,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
            )
            if (bar.targetValue != null) {
                val targetY = size.height - (bar.targetValue / ceiling).coerceIn(0f, 1f) * size.height
                drawLine(
                    color = targetColor,
                    start = Offset(x, targetY),
                    end = Offset(x + barWidth, targetY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = targetDash,
                )
            }
            val markRadius = 3.dp.toPx()
            // A bar carrying BOTH a value label and a marker used to draw them at the same height,
            // straight through each other ("238.06" reading "238✕06" on the SPEND hero, caught on
            // device 2026-08-15 - the category chart is the kit's first caller to set both on one
            // bar). The label clears the marker's own band instead of sharing it.
            val markReserve = if (bar.mark != null) markRadius * 2f + 3.dp.toPx() else 0f
            val label = bar.valueLabel
            if (label != null) {
                val layout = textMeasurer.measure(AnnotatedString(label), style = labelStyle)
                val labelX = (x + (barWidth - layout.size.width) / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                val labelY = (size.height - height - markReserve - layout.size.height - 2.dp.toPx()).coerceIn(0f, size.height)
                drawText(layout, topLeft = Offset(labelX, labelY))
            }
            if (bar.mark != null) {
                val markY = (size.height - height - markRadius - 3.dp.toPx()).coerceAtLeast(markRadius)
                drawDeckMarker(bar.mark, Offset(x + barWidth / 2f, markY), markerColor, markRadius)
            }
        }
    }
}

// ------------------------------------------------------------- range selector

/**
 * The drilldown range selector row (ticket 07/08 answers: "one range
 * selector inside drilldowns only"). Same stencil-chip treatment as
 * [com.kevin.legion.ui.fleet.RangeChip] - selected reads inverted-amber
 * (filled, dark text) rather than only a colour change on the label, per
 * ticket 03's "state reads as a shape too, not colour alone" posture already
 * used by [DeckPanels.DeckTag]'s inverted styles.
 */
@Composable
fun DeckRangeSelector(selected: DeckRange, onSelect: (DeckRange) -> Unit, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (range in DeckRange.entries) {
            val isSelected = range == selected
            Box(
                Modifier
                    .let { if (isSelected) it.background(MaterialTheme.colorScheme.primary) else it.border(1.dp, sem.faint) }
                    .clickable(enabled = !isSelected) { onSelect(range) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    range.label,
                    style = LegionType.stamp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else sem.faint,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ previews

private fun previewSparklinePoints(): List<Float?> =
    listOf(12f, 14f, null, 15f, 16f, 15.5f, null, null, 18f, 19f, 17f, 20f)

@Preview(name = "Sparkline with a gap", widthDp = 200)
@Composable
private fun PreviewDeckSparkline() = LegionTheme {
    Surface { DeckSparkline(previewSparklinePoints()) }
}

@Preview(name = "Small multiple with a gap", widthDp = 260)
@Composable
private fun PreviewDeckSmallMultiple() = LegionTheme {
    Surface { DeckSmallMultiple("OIL TEMP", "212F", previewSparklinePoints()) }
}

/**
 * The full marker vocabulary at SPARKLINE scale (mission-control ticket 15's
 * own verification step 5): one of each of the four [DeckMarkerType]s along
 * one otherwise-plain series, at the exact 54dp/2dp-stroke geometry
 * [DeckSparkline] ships at everywhere - the size ticket 06 flagged as
 * `reasoned`, not seen, and this ticket's job to actually check.
 */
private fun previewMarkerSparklinePoints(): List<Float?> =
    listOf(12f, 14f, 15f, 16f, 15.5f, 17f, 18f, 19f, 17f, 20f)

private fun previewMarkerTypes(): List<DeckMarkerType?> = listOf(
    DeckMarkerType.LOGGED, null, DeckMarkerType.ESTIMATE, null,
    DeckMarkerType.PROVISIONAL, null, null, DeckMarkerType.LOGGED, null, DeckMarkerType.ENDPOINT,
)

@Preview(name = "Sparkline, marker vocabulary (logged/estimate/provisional/endpoint)", widthDp = 200)
@Composable
private fun PreviewDeckSparklineMarkers() = LegionTheme {
    Surface { DeckSparkline(previewMarkerSparklinePoints(), markers = previewMarkerTypes()) }
}

private fun previewLineSeries(): List<DeckPoint?> {
    val dayMs = 24L * 60 * 60 * 1000
    return (0 until 14).map { i ->
        if (i == 5 || i == 6) null else DeckPoint(xMs = i * dayMs, y = 180f + 12f * i - (i * i))
    }
}

@Preview(name = "Line chart, drilldown height", widthDp = 360)
@Composable
private fun PreviewDeckLineChart() = LegionTheme {
    Surface {
        Column {
            DeckLineChart(
                series = previewLineSeries(),
                yLabel = { v -> "%.0f".format(v) },
                xLabels = (0 until 14).map { if (it % 3 == 0) "D$it" else "" },
            )
            DeckRangeSelector(selected = DeckRange.THIRTY_DAY, onSelect = {})
        }
    }
}

/** A flat comparison line - a budget/target that does not move - long enough to prove the overlay's own endpoint label lands clear of the primary series' label even when the two lines end at very different heights. */
private fun previewOverlaySeries(): List<DeckPoint?> {
    val dayMs = 24L * 60 * 60 * 1000
    return (0 until 14).map { i -> DeckPoint(xMs = i * dayMs, y = 208f) }
}

/**
 * [DeckLineChart]'s one sanctioned two-series overlay (ticket 06 answer #2):
 * mint ACTUAL against amber BUDGET, both direct-labelled at their own
 * endpoints - the L11 gate for [DeckLineOverlay] before any real screen
 * reaches for it.
 */
@Preview(name = "Line chart, two-series overlay (actual vs budget)", widthDp = 360)
@Composable
private fun PreviewDeckLineChartOverlay() = LegionTheme {
    Surface {
        Column {
            DeckLineChart(
                series = previewLineSeries(),
                yLabel = { v -> "%.0f".format(v) },
                xLabels = (0 until 14).map { if (it % 3 == 0) "D$it" else "" },
                seriesLabel = "ACTUAL",
                overlay = DeckLineOverlay(series = previewOverlaySeries(), label = "BUDGET"),
            )
            DeckRangeSelector(selected = DeckRange.THIRTY_DAY, onSelect = {})
        }
    }
}

private fun previewBars(): List<DeckBar?> = listOf(
    DeckBar("MON", 4200f, targetValue = 5000f, valueLabel = "42.00"),
    null,
    DeckBar("WED", 6100f, targetValue = 5000f, mark = DeckMarkerType.PROVISIONAL),
    DeckBar("THU", 4800f, targetValue = 5000f),
    DeckBar("FRI", 5300f, targetValue = 5000f, valueLabel = "53.00", mark = DeckMarkerType.ESTIMATE),
    null,
    DeckBar("SUN", 2100f, targetValue = 5000f),
)

@Preview(name = "Bar chart with an absent slot, dashed amber target, marks", widthDp = 360)
@Composable
private fun PreviewDeckBarChart() = LegionTheme {
    Surface { DeckBarChart(previewBars()) }
}
