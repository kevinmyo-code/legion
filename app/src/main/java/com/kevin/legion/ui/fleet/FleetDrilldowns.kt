package com.kevin.legion.ui.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.YearlyWrapped
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate

/**
 * FLEET's two in-screen drilldowns (ticket 18, merging FLEET + TELEMETRY per ticket 09's
 * resolution). Same shape as [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]: internal
 * Compose state in [com.kevin.legion.ui.FleetScreen] selects one of these (or the UPLINK
 * drilldown, which reuses [com.kevin.legion.ui.TelemetryScreen] wholesale rather than a third
 * file here - see that screen's own doc comment), never a nav-graph route with an argument
 * (`LegionRoute` deliberately carries none). Both are display-only: [state]/[rows] in, a
 * back callback out, no controller or DAO reference, matching every other drilldown/content
 * split in this codebase.
 */

/**
 * MAINTENANCE's drilldown: the full due schedule (every row [com.kevin.legion.ui.fleet.buildDueRows]
 * already built for the panel itself - ticket 18's "reuse the exact rows/ordering FleetScreen
 * already builds", read twice off the same list rather than a second query) plus the
 * service-history/build-sheet/recap counts the pre-merge FleetScreen already surfaced as
 * NOT BUILT YET. There is no service-history screen to link into (CLAUDE.md §10: no screen
 * exists for those three), so "reuse existing content" here means exactly what the old FleetScreen
 * already rendered, carried into the drilldown rather than dropped.
 */
@Composable
fun MaintenanceDrilldownScreen(
    dueRows: List<DueRowView>,
    serviceHistoryCount: Int,
    buildSheetCount: Int,
    recapCount: Int,
    /**
     * Every [MonthlyRecap] on file, newest-first - the SAME list [recapCount]
     * is `.size` of (quant-viz ticket 12: "no new DB queries"), reused here to
     * draw the RECAPS row's inline miles-driven sparkline via
     * [buildRecapMonthSlots]/[recapMonthPoints]. `FleetScreen`'s own
     * [FleetUiState.monthlyRecaps] doc explains why this is not a second read.
     */
    monthlyRecaps: List<MonthlyRecap>,
    /** OIL row's headline (quant-viz ticket 06): `OilAnalysisDao.getAll(vehicleId).size`. */
    oilAnalysisCount: Int,
    onOpenRecaps: () -> Unit,
    onOpenOilAnalysis: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "MAINTENANCE",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            // A flat LazyColumn even when dueRows is empty (not a top-level
            // if/else the way this screen used to branch) - the RECAPS/OIL
            // rows below the schedule are reachable regardless of whether
            // there is a due schedule to show, and gating the whole list on
            // dueRows would have hidden them on an otherwise-empty car.
            LazyColumn(Modifier.fillMaxSize()) {
                if (dueRows.isEmpty()) {
                    item(key = "no-schedule") {
                        Text(
                            "No maintenance schedule yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(dueRows, key = { "due-${it.label}" }) { row ->
                        // sub on its own line, never concatenated into value: DeckRow's value is
                        // unweighted TextOverflow.Visible on purpose ("a value getting clipped is
                        // a worse failure than a label running long"), so a long composed string
                        // starves the weighted label to 0dp AND pushes its own tail past the
                        // screen clip - both texts vanish. Observed on-device 2026-08-13 (QA,
                        // quant-viz pass) on a real 3,000mi-interval row.
                        Column {
                            DeckRow(
                                label = row.label,
                                value = row.value,
                                tag = if (row.overdue) {
                                    { DeckTag("OVERDUE", DeckTagStyle.INVERTED_AMBER) }
                                } else {
                                    null
                                },
                            )
                            Text(
                                row.sub,
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                item(key = "history-spacer") { Spacer(Modifier.height(14.dp)) }
                item(key = "service-history") {
                    Text(
                        "$serviceHistoryCount service record${if (serviceHistoryCount == 1) "" else "s"} on file, no screen yet",
                        style = LegionType.stamp,
                        color = sem.ghost,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    // Recaps and oil analyses each gained a real drilldown
                    // (quant-viz tickets 05/06) - only build sheets are
                    // still "no screen yet" among this trio.
                    Text(
                        "$buildSheetCount build sheet entries on file, no screen yet",
                        style = LegionType.stamp,
                        color = sem.ghost,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                item(key = "recaps-row") {
                    // RECAPS pane strip (quant-viz ticket 12): the row stays
                    // "where the recap count renders" per the ticket's own
                    // wording, with a glanceable miles-driven sparkline added
                    // beneath it once there are enough months to trend - below
                    // two recaps this is exactly the same count-only row as
                    // before, matching RecapDrilldownScreen's own "trend
                    // appears after two monthly recaps" posture stated in the
                    // same words here rather than a silently empty chart.
                    Column(Modifier.clickable(onClick = onOpenRecaps)) {
                        DeckRow(label = "Recaps", value = recapCount.toString())
                        if (monthlyRecaps.size >= 2) {
                            val slots = buildRecapMonthSlots(monthlyRecaps)
                            DeckSparkline(
                                recapMonthPoints(slots) { it.milesDriven }.map { it?.y },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            Text(
                                "miles",
                                style = LegionType.stamp,
                                color = sem.ghost,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                item(key = "oil-row") {
                    DeckRow(
                        label = "Oil Analyses",
                        value = oilAnalysisCount.toString(),
                        modifier = Modifier.clickable(onClick = onOpenOilAnalysis),
                    )
                }
            }
        }
    }
}

/**
 * RECAPS drilldown (quant-viz ticket 05): [YearlyWrapped]'s latest year (when
 * one has generated - see [YearlyWrappedPane]'s doc) pinned at the top, then
 * two [DeckLineChart] month-by-month trends (miles driven, avg MPG) built by
 * [buildRecapMonthSlots]/[recapMonthPoints], then the full [MonthlyRecap] list
 * newest-first. Reached only from [MaintenanceDrilldownScreen]'s RECAPS row -
 * this drilldown has no panel row of its own on `FleetScreen`, so its own
 * `onBack` returns the caller to MAINTENANCE, not to the FLEET panel list
 * (`FleetScreen`'s drilldown-selection `when` wires that, not this file).
 *
 * Below two recaps the trend charts are meaningless (a two-point "trend" is
 * just two numbers), so ticket 05 asks for the list alone plus a stated
 * reason rather than a chart with nothing to show - the same "say it in
 * words rather than draw an empty/degenerate chart" posture
 * [DrivesPane]'s MPG-sparkline branch in `FleetScreen.kt` already uses.
 */
@Composable
fun RecapDrilldownScreen(recapsNewestFirst: List<MonthlyRecap>, yearlyWrapped: YearlyWrapped?, onBack: () -> Unit) {
    val sem = LocalLegionSemantics.current
    // Which recap's narrative is expanded, if any - "tapping a row may show
    // narrative in a plain text block" (ticket 05), one at a time so opening a
    // second row's narrative closes the first rather than stacking an
    // unbounded amount of prose into the list.
    var expandedRecapId by remember { mutableStateOf<Long?>(null) }
    val slots = if (recapsNewestFirst.size >= 2) buildRecapMonthSlots(recapsNewestFirst) else emptyList()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "RECAPS",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            LazyColumn(Modifier.fillMaxSize()) {
                // A generated-but-absent Wrapped (no year has rolled over yet)
                // is not a gap to announce - it simply omits the pane, per
                // ticket 05 part B.
                if (yearlyWrapped != null) {
                    item(key = "wrapped-pane") { YearlyWrappedPane(yearlyWrapped) }
                }
                if (recapsNewestFirst.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No monthly recaps generated yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    if (recapsNewestFirst.size < 2) {
                        item(key = "trend-sentence") {
                            Text(
                                "trend appears after two monthly recaps",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        item(key = "miles-chart") {
                            Text(
                                "MILES DRIVEN",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            DeckLineChart(
                                series = recapMonthPoints(slots) { it.milesDriven },
                                yLabel = { "%.0f mi".format(it) },
                                xLabels = recapMonthXLabels(slots),
                            )
                        }
                        item(key = "mpg-chart") {
                            Text(
                                "AVG MPG",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            DeckLineChart(
                                series = recapMonthPoints(slots) { it.avgMpg },
                                yLabel = { "%.1f".format(it) },
                                xLabels = recapMonthXLabels(slots),
                            )
                        }
                    }
                    items(recapsNewestFirst, key = { it.id }) { recap ->
                        RecapRow(
                            recap = recap,
                            expanded = expandedRecapId == recap.id,
                            onToggle = { expandedRecapId = if (expandedRecapId == recap.id) null else recap.id },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The pinned header at the top of [RecapDrilldownScreen] (ticket 05 part B):
 * plain text [DeckRow]s over [wrapped]'s own fields, `avgMpg` `null` read as
 * "-" per the ticket's explicit call - [longestDriveMiles] gets the same
 * treatment for the same reason ("nothing invented for a field the source
 * data left null") even though the ticket text only names AVG MPG, since both
 * fields carry the identical nullable-Double shape.
 */
@Composable
private fun YearlyWrappedPane(wrapped: YearlyWrapped) {
    DeckPane(header = "Yearly Wrapped") {
        DeckRow(label = "Year", value = wrapped.year.toString())
        DeckRow(label = "Miles", value = "%.0f mi".format(wrapped.milesDriven))
        DeckRow(label = "Drives", value = wrapped.driveCount.toString())
        DeckRow(label = "Avg Mpg", value = wrapped.avgMpg?.let { "%.1f".format(it) } ?: "-")
        DeckRow(label = "Longest", value = wrapped.longestDriveMiles?.let { "%.0f mi".format(it) } ?: "-")
        DeckRow(label = "Codes", value = wrapped.codeEventCount.toString())
        DeckRow(label = "Services", value = wrapped.serviceCount.toString())
        Text(
            wrapped.narrative,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One [MonthlyRecap] in the newest-first list: headline row (month/year,
 * miles driven, an OUTLINE_MUTED [DeckTag] when [MonthlyRecap.notable] -
 * informational per ticket 03's ladder, not an advisory or an armed/ok
 * state), a drive/code/service-count subtitle, and - tapping the row -
 * [MonthlyRecap.narrative] as read-only prose (ticket 05: "tapping a row may
 * show narrative in a plain text block").
 */
@Composable
private fun RecapRow(recap: MonthlyRecap, expanded: Boolean, onToggle: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        DeckRow(
            label = "${monthAbbrevForRecap(recap.month)} ${recap.year}",
            value = "%.0f mi".format(recap.milesDriven),
            tag = if (recap.notable) { { DeckTag("NOTABLE", DeckTagStyle.OUTLINE_MUTED) } } else null,
        )
        Text(
            "${recap.driveCount} drives · ${recap.codeEventCount} codes · ${recap.serviceCount} services",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (expanded) {
            Text(
                recap.narrative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** "JAN" for [RecapRow]'s headline - same short-name shape as [FleetRows.kt]'s private `monthAbbrev`, kept separate since that one is `private` to its file. */
private fun monthAbbrevForRecap(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).uppercase()

/**
 * DRIVES' drilldown: the raw [DailyDriveLog] rows [com.kevin.legion.data.local.DailyDriveLogDao.getRecent]
 * already returns (same reuse posture as [MaintenanceDrilldownScreen]) - date, miles, MPG when the
 * day has one, and the day's own AI narrative. A day with `driveCount == 0` still renders (never
 * filtered out here, unlike [buildLastDriveSummary]'s "last drive" reading) because a drilldown's
 * job is the full log, not just the headline fact.
 */
@Composable
fun DriveHistoryDrilldownScreen(logsNewestFirst: List<DailyDriveLog>, onBack: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "DRIVE HISTORY",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            if (logsNewestFirst.isEmpty()) {
                Text(
                    "No drive logs yet. One appears the first time an OBD adapter records a finished drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(logsNewestFirst, key = { it.id }) { log ->
                        DriveLogRow(log)
                        Hairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveLogRow(log: DailyDriveLog) {
    val sem = LocalLegionSemantics.current
    val dayMs = java.time.LocalDate.of(log.year, log.month, log.day)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(shortDate(dayMs), style = LegionType.stamp, color = sem.faint)
            val mpgPart = log.avgMpg?.let { " · %.1f mpg".format(it) }.orEmpty()
            Text("${log.milesDriven.toInt()} mi$mpgPart", style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(log.narrative, style = MaterialTheme.typography.bodySmall, color = sem.faint)
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Maintenance drilldown: mixed schedule", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewMaintenanceDrilldown() = LegionTheme {
    MaintenanceDrilldownScreen(
        dueRows = listOf(
            DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - last at 132,400", overdue = true),
            DueRowView("Front Brake Pads", "in 1750 miles", "every 25,000 mi - last at 115,000", overdue = false),
        ),
        serviceHistoryCount = 12, buildSheetCount = 4, recapCount = 3,
        monthlyRecaps = listOf(
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 8, generatedAt = 0,
                milesDriven = 812.0, avgMpg = 27.1, driveCount = 24, longestDriveMiles = 140.0,
                codeEventCount = 0, serviceCount = 1, narrative = "A normal month.",
                coverImagePath = null, notable = false,
            ),
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 7, generatedAt = 0,
                milesDriven = 1_240.0, avgMpg = null, driveCount = 31, longestDriveMiles = 410.0,
                codeEventCount = 1, serviceCount = 0, narrative = "A road trip month.",
                coverImagePath = null, notable = true, notableReason = "Longest drive of the year",
            ),
        ),
        oilAnalysisCount = 2,
        onOpenRecaps = {}, onOpenOilAnalysis = {},
        onBack = {},
    )
}

@Preview(name = "Recaps drilldown: Wrapped + monthly trend", widthDp = 360, heightDp = 1200)
@Composable
private fun PreviewRecapDrilldown() = LegionTheme {
    RecapDrilldownScreen(
        recapsNewestFirst = listOf(
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 8, generatedAt = 0,
                milesDriven = 812.0, avgMpg = 27.1, driveCount = 24, longestDriveMiles = 140.0,
                codeEventCount = 0, serviceCount = 1, narrative = "A normal month, one oil change.",
                coverImagePath = null, notable = false,
            ),
            MonthlyRecap(
                vehicleId = "x", year = 2026, month = 7, generatedAt = 0,
                milesDriven = 1_240.0, avgMpg = null, driveCount = 31, longestDriveMiles = 410.0,
                codeEventCount = 1, serviceCount = 0, narrative = "A road trip month - check engine light near the end.",
                coverImagePath = null, notable = true, notableReason = "Longest drive of the year",
            ),
        ),
        yearlyWrapped = YearlyWrapped(
            vehicleId = "x", year = 2026, generatedAt = 0, milesDriven = 9_400.0, driveCount = 210,
            avgMpg = 26.4, longestDriveMiles = 410.0, notableMonths = 2, codeEventCount = 3,
            serviceCount = 5, narrative = "A steady year with one long road trip.", coverImagePath = null,
        ),
        onBack = {},
    )
}

@Preview(name = "Drive history drilldown: a few logged days", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDriveHistoryDrilldown() = LegionTheme {
    DriveHistoryDrilldownScreen(
        logsNewestFirst = listOf(
            DailyDriveLog(
                vehicleId = "x", year = 2026, month = 8, day = 7, generatedAt = 0,
                milesDriven = 42.0, avgMpg = 27.3, driveCount = 2, codeEventCount = 0,
                narrative = "A normal commute day, nothing notable.",
            ),
            DailyDriveLog(
                vehicleId = "x", year = 2026, month = 8, day = 6, generatedAt = 0,
                milesDriven = 0.0, avgMpg = null, driveCount = 0, codeEventCount = 0,
                narrative = "Parked all day.",
            ),
        ),
        onBack = {},
    )
}
