package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.relativeAge
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.VehicleController
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

/**
 * FLEET-specific pure logic and rows for ticket 09 (resolution §1: LIVE / DUE
 * / FAULTS / NOT BUILT YET). The shared, aspect-agnostic furniture
 * (`SectionHeader`, `Hairline`, `ReadingRow`, `NotBuiltRow`) lives in
 * `ui/common/CommonRows.kt` - see that file's doc comment. Everything here is
 * either a pure function (unit-tested in `FleetRowsTest`, no Android/Room
 * dependency) or a thin display-only Composable; nothing here writes to the
 * database, matching this ticket's read-only scope.
 */

// --------------------------------------------------------------- LIVE (pure)

/**
 * One row of the LIVE block: a label, its last-seen value, and how stale that value is.
 *
 * [pid] (mission-control ticket 16 follow-up, "get FLEET's tile row above the fold") is the raw
 * OBD PID this reading came from - "0105", "ATRV", "0107" - carried through so [UplinkPane] can
 * render these as [com.kevin.legion.ui.common.DeckFeedRow]'s `code` column, same PID-as-code shape
 * [ThemePreview.kt]'s own "Live PIDs" section already demonstrates. Defaults to `""` so every
 * pre-existing 3-arg `LiveRowView(label, value, sub)` construction site (previews, tests) keeps
 * compiling unchanged.
 */
data class LiveRowView(val label: String, val value: String, val sub: String, val pid: String = "")

/**
 * The fixed, small set of slow-changing PIDs [com.kevin.legion.vehicle.TelemetryRecorder]
 * writes to `obd_samples` that are worth a driver glancing at (coolant temp,
 * battery voltage, long-term fuel trim). Deliberately not the full PID set
 * TelemetryRecorder samples (RPM, MAF, speed, short-term trim) - those move
 * every tick and belong to a trend chart this ticket does not build, not a
 * static "last seen" readout. Raw PID codes match [OdbSample.pid] exactly as
 * TelemetryRecorder writes them - "0105", "ATRV", "0107" - see that object's
 * `run` loop.
 */
private data class LiveGauge(val pid: String, val label: String, val format: (Double) -> String)

private val LIVE_GAUGES = listOf(
    LiveGauge("0105", "Coolant") { v -> "${v.toInt()} C" },
    LiveGauge("ATRV", "Battery") { v -> "%.1f V".format(v) },
    LiveGauge("0107", "Fuel trim, long") { v -> "%+.1f %%".format(v) },
)

/** PIDs [buildLiveRows] wants a latest sample for - drives the DAO reads in the state holder. */
internal val LIVE_GAUGE_PIDS: List<String> = LIVE_GAUGES.map { it.pid }

/**
 * Formats each gauge's latest sample (or omits the row entirely if this
 * install has never recorded that PID - never a fabricated "no data" value
 * standing in for a number). `internal` for direct unit testing.
 */
internal fun buildLiveRows(samplesByPid: Map<String, OdbSample?>, now: Long): List<LiveRowView> =
    LIVE_GAUGES.mapNotNull { gauge ->
        val sample = samplesByPid[gauge.pid] ?: return@mapNotNull null
        LiveRowView(gauge.label, gauge.format(sample.value), relativeAge(sample.timestamp, now), pid = gauge.pid)
    }

// ------------------------------------------------------------- DUE (pure)

/** One row of the DUE block, already resolved to display strings. */
data class DueRowView(
    val label: String,
    val value: String,
    val sub: String,
    val overdue: Boolean,
    /**
     * elapsed/interval on the row's own axis (miles when [toDueRow] chose the
     * miles axis, else time), for [com.kevin.legion.ui.common.DeckMeter] drawn
     * under the row's text on `FleetScreen`'s MAINTENANCE panel (quant-viz
     * ticket 05 part C). `null` when the item has no interval on file to
     * divide by - no meter is drawn, never a meter frozen at zero, matching
     * [toDueRow]'s own "-" for a value with the same cause. See [dueFraction]
     * for the math. Defaults to `null` so the many `DueRowView(...)` preview/
     * test construction sites that predate this field keep compiling.
     */
    val fraction: Float? = null,
)

/**
 * Builds the DUE block's rows from a vehicle's [MaintenanceItem]s. Items with
 * no anchor at all ([VehicleController.isUnknown]) are excluded - they are
 * not "due", they are "we don't know yet", a different state this block does
 * not speak to (see [VehicleController.unknownItems]'s doc).
 *
 * **Ordering.** Overdue items first (stable order, not re-sorted among
 * themselves), then not-yet-due items in their original order. Deliberately
 * NOT sorted by "soonest remaining" across items that mix a miles-remaining
 * candidate against a days-remaining one - [VehicleController.computeNextService]'s
 * doc comment explains at length why any such cross-axis comparison is really
 * a smuggled-in rate estimate ("miles per day"), which Kevin explicitly
 * rejected. This block reports each item's own remaining value on its own
 * axis and lets the reader compare, rather than picking a winner for them.
 *
 * `internal` rather than `private` so [FleetRowsTest] can drive it directly.
 */
internal fun buildDueRows(items: List<MaintenanceItem>, currentMileage: Int, now: Long): List<DueRowView> {
    val anchored = items.filterNot { VehicleController.isUnknown(it) }
    val (overdue, upcoming) = anchored.partition { VehicleController.isDue(it, currentMileage, now) }
    return overdue.map { toDueRow(it, currentMileage, now, overdue = true) } +
        upcoming.map { toDueRow(it, currentMileage, now, overdue = false) }
}

/**
 * One item's DUE row. Prefers the miles axis for both the headline value and
 * the subtitle when the item is anchored on both miles and time (matches
 * [buildDueRows]'s "report each item's own remaining value" posture - miles
 * is picked as the single axis shown per item only for column tidiness, never
 * because it is judged "more due"). Falls back to the time axis, then to a
 * bare "no interval" when the item has an anchor but no interval was ever
 * looked up for it (a lookup miss, or a driver-logged service with nothing
 * from [VehicleController.lookupServiceIntervals] to pair it with).
 */
private fun toDueRow(item: MaintenanceItem, currentMileage: Int, now: Long, overdue: Boolean): DueRowView {
    val milesAxis = item.intervalMiles != null && item.lastDoneMileage != null
    val timeAxis = item.intervalMonths != null && item.lastDoneDate != null

    val sub = when {
        milesAxis -> "every ${groupThousands(item.intervalMiles!!)} mi - last at ${groupThousands(item.lastDoneMileage!!)}"
        timeAxis -> "every ${item.intervalMonths} mo - last ${shortDate(item.lastDoneDate!!)}"
        item.neverDone -> "never logged"
        else -> "no interval on file"
    }

    val value = when {
        overdue -> "OVERDUE"
        milesAxis -> {
            val remaining = (item.intervalMiles!! - (currentMileage - item.lastDoneMileage!!)).toLong()
            "in " + VehicleController.formatRemaining(remaining, VehicleController.ScheduleUnit.MILES)
        }
        timeAxis -> {
            val intervalMs = item.intervalMonths!!.toLong() * 30L * 24 * 60 * 60 * 1000
            val remainingDays = (intervalMs - (now - item.lastDoneDate!!)) / (24 * 60 * 60 * 1000)
            "in " + VehicleController.formatRemaining(remainingDays, VehicleController.ScheduleUnit.DAYS)
        }
        else -> "-"
    }

    return DueRowView(item.serviceName, value, sub, overdue, dueFraction(item, currentMileage, now, overdue))
}

/**
 * [DueRowView.fraction]'s pure math (quant-viz ticket 05 part C): elapsed
 * over interval, on the SAME axis [toDueRow] picked for that row's headline
 * value - miles when the item is anchored on both [MaintenanceItem.intervalMiles]
 * and [MaintenanceItem.lastDoneMileage], else the time axis, `null` when
 * neither anchor/interval pair is present (nothing to divide by).
 *
 * [overdue] short-circuits to `1f` rather than letting the ratio run past 1.0
 * for a badly-overdue item - a meter drawing past its own right edge would
 * read as a bug, not as "very overdue"; [DueRowView.overdue]'s existing flag
 * and wording already carry that signal, so the meter only needs to stop
 * where its track ends. `internal` for direct unit testing, same posture as
 * [buildDueRows].
 */
internal fun dueFraction(item: MaintenanceItem, currentMileage: Int, now: Long, overdue: Boolean): Float? {
    if (overdue) return 1f
    val milesAxis = item.intervalMiles != null && item.lastDoneMileage != null
    val timeAxis = item.intervalMonths != null && item.lastDoneDate != null
    return when {
        milesAxis -> {
            val elapsed = (currentMileage - item.lastDoneMileage!!).toFloat()
            (elapsed / item.intervalMiles!!.toFloat()).coerceIn(0f, 1f)
        }
        timeAxis -> {
            val intervalMs = item.intervalMonths!!.toLong() * 30L * 24 * 60 * 60 * 1000
            val elapsedMs = (now - item.lastDoneDate!!).toFloat()
            (elapsedMs / intervalMs.toFloat()).coerceIn(0f, 1f)
        }
        else -> null
    }
}

/** "132400" -> "132,400". No currency, no decimals - a mileage/interval figure, not money. */
internal fun groupThousands(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

// ---------------------------------------------------------- FAULTS (pure)

/** One distinct stored code, first seen at [firstSeenMs]. */
data class FaultRow(val code: String, val firstSeenMs: Long)

/**
 * Flattens [CodeEvent]'s `codesJson` (several codes can trip in one event)
 * into one row per DISTINCT code, keeping the EARLIEST timestamp any event
 * carried it - "first seen" means first, not most recent. `internal` for
 * direct unit testing, same reasoning as [buildDueRows].
 */
internal fun distinctFaultsByFirstSeen(events: List<CodeEvent>): List<FaultRow> {
    val firstSeen = mutableMapOf<String, Long>()
    for (event in events) {
        val codes = runCatching { JSONArray(event.codesJson) }.getOrNull() ?: continue
        for (i in 0 until codes.length()) {
            val code = codes.optString(i).takeIf { it.isNotBlank() } ?: continue
            val existing = firstSeen[code]
            if (existing == null || event.timestamp < existing) firstSeen[code] = event.timestamp
        }
    }
    // Newest-first: a code first seen yesterday is more likely to still be
    // relevant to the driver than one first seen a year ago.
    return firstSeen.entries.sortedByDescending { it.value }.map { FaultRow(it.key, it.value) }
}

/** The visible slice of the UPLINK STORED CODES list plus a worded overflow count - same shape
 * [com.kevin.legion.ui.AlertsSummary]/`capAlertRows` uses for HOME's ALERTS pane. */
data class FaultRowsSummary(val visible: List<Pair<FaultRow, String?>>, val overflowCount: Int)

/**
 * Caps STORED CODES at [max] (two, mission-control ticket 16 - Kevin's call). UPLINK's list was
 * unbounded and on his real car (6 DTCs) overran the whole FLEET root, pushing MAINTENANCE,
 * DRIVES and CARS below the fold - breaking ticket 05's "a root shows its hero plus one full row
 * of tiles without scrolling". Never a silent truncation: [FaultRowsSummary.overflowCount] is
 * rendered as a worded "AND N MORE" row (CLAUDE.md §4's "said in words" rule - never a bare count
 * badge), same "reported, never silent" posture `capAlertRows` already set for HOME. `internal`
 * for direct unit testing, same reasoning as [distinctFaultsByFirstSeen].
 */
internal fun capFaultRows(faults: List<Pair<FaultRow, String?>>, max: Int = 2): FaultRowsSummary =
    if (faults.size <= max) FaultRowsSummary(faults, 0) else FaultRowsSummary(faults.take(max), faults.size - max)

// ------------------------------------------------------------- DRIVES (pure)

/**
 * The DRIVES panel's fixed reading, built from [com.kevin.legion.data.local.DailyDriveLogDao.getRecent]
 * (ticket 18: "reuse existing data loading" - [com.kevin.legion.vehicle.DailyDriveLogController]
 * already aggregates TRIP_MILES/MPG_TRIP into this table every hour, so this
 * panel adds no new query, only a display shape over rows that already exist).
 */
data class DriveSummaryView(val headline: String, val sub: String, val hasData: Boolean)

/**
 * The most recent day with at least one finished drive - a day with a
 * [DailyDriveLog] row but `driveCount == 0` (the hourly refresh writes one for
 * every day, driven or not, see that controller's own doc) is not a "last
 * drive" and is skipped rather than reported as one.
 *
 * [now] anchors [relativeAge] against the day's own local midnight, not
 * [DailyDriveLog.generatedAt] - the driver cares how long ago they drove, not
 * how long ago the rollup last recomputed itself.
 */
internal fun buildLastDriveSummary(logsNewestFirst: List<DailyDriveLog>, now: Long): DriveSummaryView {
    val last = logsNewestFirst.firstOrNull { it.driveCount > 0 }
        ?: return DriveSummaryView("NO DRIVES LOGGED", "nothing recorded yet", hasData = false)
    val dayMs = LocalDate.of(last.year, last.month, last.day)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val mpgPart = last.avgMpg?.let { " · %.1f mpg".format(it) }.orEmpty()
    val driveWord = if (last.driveCount == 1) "drive" else "drives"
    return DriveSummaryView(
        headline = "${last.milesDriven.toInt()} mi$mpgPart",
        sub = "${last.driveCount} $driveWord · ${relativeAge(dayMs, now)}",
        hasData = true,
    )
}

/**
 * The DRIVES panel's MPG trend, oldest-first (matches [com.kevin.legion.ui.common.DeckSparkline]'s
 * index-ordered contract - see that composable's doc for why a sparkline
 * never carries timestamps). [logsNewestFirst] comes straight off
 * `getRecent`'s own ordering, so this only reverses it, no new query and no
 * new aggregation (ticket 18 scope: MPG history reuses what
 * [com.kevin.legion.vehicle.TelemetryRecorder]'s per-drive `MPG_TRIP` write
 * already rolled into the daily log, not a fresh average).
 *
 * A day that logged driving but never finished a fuel-integrated trip (too
 * short - see [DailyDriveLog.avgMpg]'s own nullability) is a GAP, not a zero,
 * same rule [DeckSparkline]'s file doc states for every deck chart.
 */
internal fun buildMpgSparkline(logsNewestFirst: List<DailyDriveLog>): List<Float?> =
    logsNewestFirst.asReversed().map { it.avgMpg?.toFloat() }

/**
 * The DRIVES panel's second, sibling sparkline (quant-viz ticket 12): daily
 * [DailyDriveLog.milesDriven], oldest-first, off the exact same [logsNewestFirst]
 * rows [buildMpgSparkline] already receives - no second query, matching that
 * function's own doc.
 *
 * Unlike [buildMpgSparkline]'s `avgMpg` (nullable - a day can log driving with
 * no fuel-integrated trip finished), [DailyDriveLog.milesDriven] is a
 * non-null `Double` that the hourly rollup writes for every day whether or
 * not it was driven (see [buildLastDriveSummary]'s doc), so a day inside this
 * window is never a genuine gap here - `0.0` on an undriven day is a real
 * zero, the same "gap-vs-zero" distinction CLAUDE.md §4 rule 6 states for
 * money, read onto miles: nothing in [logsNewestFirst] is missing, so nothing
 * here is `null`. The `Float?` return type still matches [DeckSparkline]'s
 * general contract rather than narrowing to `Float`, so a future caller that
 * feeds a genuinely sparse window (e.g. a car with days it did not exist yet)
 * is not silently miscompiled into treating an absent day as zero.
 */
internal fun buildMilesSparkline(logsNewestFirst: List<DailyDriveLog>): List<Float?> =
    logsNewestFirst.asReversed().map { it.milesDriven.toFloat() }

// ------------------------------------------------------------- RECAPS (pure)

/**
 * One calendar month's slot in the RECAPS trend charts (quant-viz ticket 05
 * part A). [milesDriven]/[avgMpg] are `null` when no [MonthlyRecap] exists
 * for that month - a GAP, never a `0f`, matching [DeckChartData.kt]'s file
 * doc invariant applied here without going through `dailyBuckets` (recaps are
 * monthly, not daily, so this module builds its own month axis rather than
 * reusing that day-grained helper).
 */
internal data class RecapMonthSlot(val year: Int, val month: Int, val milesDriven: Float?, val avgMpg: Float?)

/**
 * Every calendar month from the EARLIEST recap on file through the LATEST,
 * inclusive, one [RecapMonthSlot] per month - a month with no [MonthlyRecap]
 * row (the generator skipped a month, or the car did not exist yet) is a
 * `null`-valued gap slot rather than being omitted from the axis, so the
 * chart's x-spacing stays evenly monthly. `internal` for direct unit testing
 * (ticket 05's "month-slot builder (missing month -> null)").
 */
internal fun buildRecapMonthSlots(recaps: List<MonthlyRecap>): List<RecapMonthSlot> {
    if (recaps.isEmpty()) return emptyList()
    val byKey = recaps.associateBy { it.year * 12 + (it.month - 1) }
    val minKey = byKey.keys.min()
    val maxKey = byKey.keys.max()
    return (minKey..maxKey).map { key ->
        val year = key / 12
        val month = key % 12 + 1
        val recap = byKey[key]
        RecapMonthSlot(year, month, recap?.milesDriven?.toFloat(), recap?.avgMpg?.toFloat())
    }
}

/**
 * Maps [slots] into [com.kevin.legion.ui.common.DeckPoint]`?` for
 * [com.kevin.legion.ui.common.DeckLineChart], picking [value] per slot ([RecapMonthSlot.milesDriven]
 * or [RecapMonthSlot.avgMpg]) - a `null` field stays a `null` point, the same
 * gap the slot itself already carries. `xMs` is each month's local
 * calendar-day-1 start; [DeckLineChart] plots by index, not by this
 * timestamp, but every other [com.kevin.legion.ui.common.DeckPoint] producer
 * in the kit carries a real one and this keeps the type honest rather than
 * stuffing in a sentinel.
 */
internal fun recapMonthPoints(slots: List<RecapMonthSlot>, value: (RecapMonthSlot) -> Float?): List<com.kevin.legion.ui.common.DeckPoint?> =
    slots.map { slot ->
        val y = value(slot) ?: return@map null
        val xMs = LocalDate.of(slot.year, slot.month, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        com.kevin.legion.ui.common.DeckPoint(xMs = xMs, y = y)
    }

/** "JAN" style short month name for [recapMonthXLabels] - `java.time.Month` avoids a manual 12-entry table. */
private fun monthAbbrev(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).uppercase()

/**
 * [slots]' x-axis labels, thinned to January and July (ticket 05 part A) -
 * every other index is blank, per [com.kevin.legion.ui.common.DeckLineChart]'s
 * own "callers thin their own labels" contract.
 */
internal fun recapMonthXLabels(slots: List<RecapMonthSlot>): List<String> =
    slots.map { slot -> if (slot.month == 1 || slot.month == 7) "${monthAbbrev(slot.month)} ${slot.year}" else "" }

// ------------------------------------------------------------------ rows

/**
 * One stored fault: code, description, first-seen. **Mandated fix from the
 * prototype render (ticket 09 resolution §1):** the description sits in
 * plain ink, never [com.kevin.legion.ui.theme.LegionSemantics.quarantined] -
 * red is reserved for the code itself, which is the actual alarm token. A
 * description is prose explaining the code, not a second alarm.
 *
 * [description] is null when neither [com.kevin.legion.vehicle.DtcDescriptions.loadSeed]
 * nor `loadLearned` has an entry for [row]'s code - rendered honestly as
 * "not identified locally" in faint ink rather than a blank line, so the row
 * never reads as broken.
 */
@Composable
fun FaultRowView(row: FaultRow, description: String?) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.code, style = LegionType.reading, color = sem.quarantined)
            Text(
                description ?: "not identified locally",
                style = MaterialTheme.typography.bodyMedium,
                color = if (description != null) MaterialTheme.colorScheme.onSurface else sem.faint,
            )
            Text("first seen ${shortDate(row.firstSeenMs)}", style = LegionType.stamp, color = sem.faint)
        }
    }
}
