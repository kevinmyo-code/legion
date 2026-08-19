package com.kevin.legion.ui.fleet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.PidSummary
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.ObdHistory

/**
 * The TELEMETRY screen's pure logic and rows, for `ui/TelemetryScreen.kt`.
 * Same split as [FleetRows.kt] and `CarRows.kt`: pure functions here are
 * unit-tested in `TelemetryRowsTest` with no Room or Android dependency.
 *
 * **What this screen is for.** `obd_samples` is the app's one compounding time
 * series - 11,532 rows arrived from Midnight AI on 2026-08-04 alone - and
 * until now the only way to see any of it was to ASK the assistant out loud
 * (`get_trend` in [com.kevin.legion.service.LiveToolbox]). FLEET's LIVE block
 * shows the last value of three slow gauges and nothing else.
 *
 * The shaping logic it needs was ported and then left with no caller:
 * [ObdHistory.downsample] (a million rows a year will not go on a Canvas),
 * [ObdHistory.pidLabel], and [ObdHistory.splitDrives]. This screen is that
 * caller.
 */

// --------------------------------------------------------------- pure

/**
 * How far back the chart looks. [spanMs] null means "everything recorded" -
 * bounded in practice by [com.kevin.legion.data.local.OdbSampleDao.getRangeNewestFirst]'s
 * row cap, not by time.
 */
enum class TelemetryRange(val label: String, val spanMs: Long?) {
    WEEK("7D", 7L * 24 * 60 * 60 * 1000),
    MONTH("30D", 30L * 24 * 60 * 60 * 1000),
    YEAR("1Y", 365L * 24 * 60 * 60 * 1000),
    ALL("ALL", null),
}

/**
 * The window's start. [TelemetryRange.ALL] returns 0 rather than the oldest
 * sample's timestamp: the query is a `>=` bound, so 0 is exactly "no lower
 * bound" and needs no knowledge of what the car has recorded. A negative
 * result (a range longer than the epoch) is floored at 0.
 */
fun rangeStartMs(range: TelemetryRange, now: Long): Long =
    range.spanMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L

/**
 * Decimal places for a reading, chosen by its stored unit
 * ([com.kevin.legion.data.local.OdbSample.unit], written by
 * [com.kevin.legion.vehicle.TelemetryRecorder]).
 *
 * Driven by the unit rather than by the magnitude of the value, because
 * magnitude lies at the edges: an idling engine at 800 rpm and a battery at
 * 12.4 V are both three-digit-or-less numbers that want completely different
 * precision, and "0.0 rpm" for a stopped engine reads as a broken sensor.
 */
internal fun decimalsFor(unit: String): Int = when (unit) {
    // "°C"/"C" is the raw stored unit; "°F" is what a temperature series carries once
    // [com.kevin.legion.util.Temp] has relabelled its axis to the driver's chosen unit (ticket 07) -
    // both read as whole numbers, same as rpm and km/h.
    "rpm", "km/h", "°C", "C", "°F" -> 0
    else -> 1
}

/** "1,847 rpm" / "12.4 V" / "-2.3 %". Thousands are grouped; the unit is whatever the sample stored. */
fun formatReading(value: Double, unit: String): String {
    val decimals = decimalsFor(unit)
    val rendered = if (decimals == 0) {
        groupThousands(Math.round(value).toInt())
    } else {
        "%.${decimals}f".format(value)
    }
    return if (unit.isBlank()) rendered else "$rendered $unit"
}

/**
 * PIDs in the order a driver would look for them, with anything unrecognised
 * kept (in its original order) at the end rather than dropped - a car that
 * answers a PID this list has never heard of is a car with MORE data, not a
 * broken one, and silently hiding it is the same sin as
 * [com.kevin.legion.vehicle.ObdHistory.pidLabel] returning the raw code
 * instead of crashing.
 */
private val PID_DISPLAY_ORDER = listOf(
    "010C", "010D", "0105", "0104", "0110", "0106", "0107", "010F", "012F", "ATRV",
    "MPG_TRIP", "TRIP_MILES", "COLD_START",
)

fun orderedPids(recorded: List<String>): List<String> {
    val known = PID_DISPLAY_ORDER.filter { it in recorded }
    val unknown = recorded.filterNot { it in PID_DISPLAY_ORDER }
    return known + unknown
}

/**
 * One PID's chart-ready series. [truncated] is true when the query's row cap
 * cut the window short, which the screen states outright - a chart that
 * silently covers less than its axis claims is exactly the kind of quiet lie
 * CLAUDE.md §4 rule 5 exists to stop, applied to a display instead of an
 * ingestion.
 */
data class TelemetrySeries(
    val points: List<Pair<Long, Double>>,
    val unit: String,
    val truncated: Boolean,
)

/**
 * Shapes raw samples into a drawable series: oldest-first, at most
 * [maxPoints] averaged buckets ([ObdHistory.downsample]).
 *
 * [rowCap] is the limit the query ran with; [rawCount] the rows it returned.
 * Equality means the cap was hit and older samples inside the window were
 * never read. A window that returns exactly `rowCap` rows and happens to have
 * had no more is reported as truncated too - the query cannot tell those
 * apart, and over-stating the uncertainty is the safe direction.
 */
fun buildSeries(
    samples: List<Pair<Long, Double>>,
    unit: String,
    rawCount: Int,
    rowCap: Int,
    maxPoints: Int = 200,
): TelemetrySeries = TelemetrySeries(
    points = ObdHistory.downsample(samples, maxPoints),
    unit = unit,
    truncated = rawCount >= rowCap,
)

/**
 * The header line under a PID's chart: min, average, max, and how many rows
 * the figures came from.
 *
 * **A zero-count summary is treated as absent, not as zeroes.** Room reads
 * SQL's `NULL` aggregate over an empty window into
 * [PidSummary]'s non-null `Double`s as `0.0`, so a PID with no samples in the
 * window would otherwise render a confident "0.0 / 0.0 / 0.0" that reads as
 * measured data. Count is the only field that is honest there, so it is the
 * one the check hangs off.
 */
fun summaryLine(summary: PidSummary?, unit: String): String? {
    if (summary == null || summary.count == 0) return null
    return "min ${formatReading(summary.min, unit)}  ·  " +
        "avg ${formatReading(summary.avg, unit)}  ·  " +
        "max ${formatReading(summary.max, unit)}  ·  " +
        "${groupThousands(summary.count)} readings"
}

/** "Jun 2, 2025 - Jun 12, 2026", or the single date when a window holds one day. */
fun spanLine(firstMs: Long?, lastMs: Long?): String? {
    if (firstMs == null || lastMs == null) return null
    val from = shortDate(firstMs)
    val to = shortDate(lastMs)
    return if (from == to) from else "$from - $to"
}

// --------------------------------------------------------------- rows

/**
 * The chart. A plain polyline over the window, no axes and no grid: this is a
 * shape-of-the-data view, and the exact figures are already stated in words by
 * [summaryLine] directly above it, where they are readable by a screen reader
 * and in greyscale. Drawing them a second time as tick labels would be
 * decoration.
 *
 * A flat series (min == max, e.g. a battery that never moved off 12.4 V) draws
 * down the vertical middle rather than dividing by a zero range.
 */
@Composable
fun TelemetryChart(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(160.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (points.size < 2) return@Canvas
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val minX = xs.first().toDouble()
        val spanX = (xs.last() - xs.first()).toDouble().coerceAtLeast(1.0)
        val minY = ys.min()
        val spanY = (ys.max() - minY).takeIf { it > 0.0 }

        fun px(index: Int): Offset {
            val x = ((xs[index] - minX) / spanX * size.width).toFloat()
            // Screen y grows downward; a higher reading must sit higher up.
            val y = if (spanY == null) size.height / 2f
            else (size.height - ((ys[index] - minY) / spanY * size.height)).toFloat()
            return Offset(x, y)
        }

        val path = Path().apply {
            val start = px(0)
            moveTo(start.x, start.y)
            for (i in 1 until points.size) {
                val point = px(i)
                lineTo(point.x, point.y)
            }
        }
        drawLine(
            color = sem.ruleFaint,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
        drawPath(path, color = line, style = Stroke(width = 2f))
    }
}

/** One selectable PID in the picker strip. Selected reads as a word, not only as a colour. */
@Composable
fun PidChip(pid: String, selected: Boolean, onSelect: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Text(
        text = ObdHistory.pidLabel(pid),
        style = LegionType.stamp,
        color = if (selected) MaterialTheme.colorScheme.primary else sem.faint,
        modifier = Modifier
            .clickable(enabled = !selected, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** One range option (7D / 30D / 1Y / ALL). Same treatment as [PidChip]. */
@Composable
fun RangeChip(range: TelemetryRange, selected: Boolean, onSelect: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Text(
        text = range.label,
        style = LegionType.stamp,
        color = if (selected) MaterialTheme.colorScheme.primary else sem.faint,
        modifier = Modifier
            .clickable(enabled = !selected, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ------------------------------------------------------------------------ previews

private fun previewPoints(): List<Pair<Long, Double>> {
    val start = 1_753_000_000_000L
    return (0 until 120).map { i ->
        (start + i * 30_000L) to (800.0 + 900 * kotlin.math.sin(i / 9.0) + i * 3)
    }
}

@Preview(name = "Telemetry chart: rpm over a drive", widthDp = 360)
@Composable
private fun PreviewTelemetryChart() = LegionTheme {
    Surface {
        Column {
            Text(
                summaryLine(
                    PidSummary(min = 612.0, max = 3410.0, avg = 1847.4, count = 5242, firstMs = 0, lastMs = 0),
                    "rpm",
                ).orEmpty(),
                style = LegionType.stamp,
                modifier = Modifier.padding(12.dp),
            )
            TelemetryChart(previewPoints())
        }
    }
}

@Preview(name = "Telemetry chart: a reading that never moved", widthDp = 360)
@Composable
private fun PreviewTelemetryChartFlat() = LegionTheme {
    Surface {
        TelemetryChart((0 until 40).map { i -> (1_753_000_000_000L + i * 30_000L) to 12.4 })
    }
}

@Preview(name = "Picker chips", widthDp = 360)
@Composable
private fun PreviewChips() = LegionTheme {
    Surface {
        Row {
            PidChip("010C", selected = true, onSelect = {})
            PidChip("0105", selected = false, onSelect = {})
            RangeChip(TelemetryRange.ALL, selected = true, onSelect = {})
            RangeChip(TelemetryRange.WEEK, selected = false, onSelect = {})
        }
    }
}
