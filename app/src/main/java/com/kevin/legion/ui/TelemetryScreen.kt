package com.kevin.legion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.PidSummary
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.ReadingRow
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.fleet.PidChip
import com.kevin.legion.ui.fleet.RangeChip
import com.kevin.legion.ui.fleet.TelemetryChart
import com.kevin.legion.ui.fleet.TelemetryRange
import com.kevin.legion.ui.fleet.TelemetrySeries
import com.kevin.legion.ui.fleet.buildSeries
import com.kevin.legion.ui.fleet.formatReading
import com.kevin.legion.ui.fleet.groupThousands
import com.kevin.legion.ui.fleet.orderedPids
import com.kevin.legion.ui.fleet.rangeStartMs
import com.kevin.legion.ui.fleet.spanLine
import com.kevin.legion.ui.fleet.summaryLine
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.vehicle.ObdHistory
import com.kevin.legion.vehicle.VehicleController

/**
 * `fleet/telemetry` - the recorded `obd_samples` history for the ACTIVE car.
 *
 * **Everything on this screen is the active car's.** There is no per-car
 * selector here; switching cars is `fleet/cars`' job ([CarsScreen]) and doing
 * it in two places would give the driver two disagreeing notions of "the car".
 * The header states which car these figures belong to for exactly that reason -
 * a chart with no subject is how the imported Outlander's 5,242 samples came
 * to be read as the placeholder Cherokee's in the first place.
 *
 * **Two different windows, on purpose.** [summaryLine]'s min/avg/max come from
 * [com.kevin.legion.data.local.OdbSampleDao.summarize], which aggregates in
 * SQL across the WHOLE selected range. The chart comes from
 * [com.kevin.legion.data.local.OdbSampleDao.getRangeNewestFirst], which is
 * capped at [ROW_CAP] rows. So the numbers are always exact even when the line
 * covers less than the range - and when it does, the screen says so rather
 * than letting the shorter line imply the car was parked.
 *
 * Split per `compose-state-holder-ui-split`: [TelemetryScreen] owns the reads,
 * [TelemetryContent] is plain state plus callbacks and is what the `@Preview`s
 * exercise.
 */
private const val ROW_CAP = 20_000

data class TelemetryUiState(
    val loading: Boolean = true,
    val carLabel: String = "",
    /** All-time count for this car, independent of the selected range. */
    val totalCount: Int = 0,
    val firstSampleMs: Long? = null,
    val lastSampleMs: Long? = null,
    val pids: List<String> = emptyList(),
    val selectedPid: String? = null,
    val range: TelemetryRange = TelemetryRange.ALL,
    val series: TelemetrySeries? = null,
    val summary: PidSummary? = null,
    val unit: String = "",
)

@Composable
fun TelemetryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(TelemetryUiState()) }
    var selectedPid by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf(TelemetryRange.ALL) }

    // Pass one: the car and what it has recorded at all. Runs once - none of
    // it changes while the screen is open, since the active car cannot be
    // switched from here.
    LaunchedEffect(Unit) {
        val vehicle = VehicleController.currentVehicle(context)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val pids = orderedPids(dao.recordedPids(vehicle.obdMac))
        // AWAIT FIRST, COPY SECOND - same fix as ui/LedgerScreen.kt's reload
        // effect, applied here because this screen has the identical shape:
        // two LaunchedEffects writing one `state` var, with suspend calls
        // inside a `state.copy(...)` argument list.
        //
        // Kotlin evaluates the copy's RECEIVER before its arguments, so `state`
        // is captured, the DAO calls suspend, and the assignment then lands a
        // snapshot that is stale by however long the database took - silently
        // discarding whatever pass two wrote in the meantime. On the ledger
        // screen this made a whole section disappear with no crash and nothing
        // in logcat; here it would drop a chart the driver had just selected.
        //
        // Found by auditing for the pattern after the ledger bug, not by a
        // failure - this one has not been seen in the wild, and the point is
        // that it would not announce itself if it happened.
        val carLabel = VehicleController.displayLabel(vehicle).ifBlank { vehicle.name }
        val totalCount = dao.totalCount(vehicle.obdMac)
        val firstSampleMs = dao.firstSampleMs(vehicle.obdMac)
        val lastSampleMs = dao.lastSampleMs(vehicle.obdMac)
        state = state.copy(
            loading = false,
            carLabel = carLabel,
            totalCount = totalCount,
            firstSampleMs = firstSampleMs,
            lastSampleMs = lastSampleMs,
            pids = pids,
        )
        if (selectedPid == null) selectedPid = pids.firstOrNull()
    }

    // Pass two: the selected PID's window. Re-runs on every pick.
    LaunchedEffect(selectedPid, range) {
        val pid = selectedPid ?: return@LaunchedEffect
        val vehicle = VehicleController.currentVehicle(context)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val now = System.currentTimeMillis()
        val fromMs = rangeStartMs(range, now)
        val rows = dao.getRangeNewestFirst(vehicle.obdMac, pid, fromMs, now, ROW_CAP)
        // The unit comes from the newest sample for this PID overall, not from
        // the window: an empty window still has to label its (absent) axis, and
        // TelemetryRecorder writes one unit per PID for the life of the row.
        val unit = rows.firstOrNull()?.unit ?: dao.getLatest(vehicle.obdMac, pid, 1).firstOrNull()?.unit.orEmpty()
        val summary = dao.summarize(vehicle.obdMac, pid, fromMs, now)
        state = state.copy(
            selectedPid = pid,
            range = range,
            unit = unit,
            summary = summary,
            series = buildSeries(
                samples = rows.map { it.timestamp to it.value },
                unit = unit,
                rawCount = rows.size,
                rowCap = ROW_CAP,
            ),
        )
    }

    TelemetryContent(
        state = state,
        onBack = onBack,
        onSelectPid = { selectedPid = it },
        onSelectRange = { range = it },
    )
}

/** Plain UI: [state] plus callbacks, no Room reference - see the file doc comment. */
@Composable
fun TelemetryContent(
    state: TelemetryUiState,
    onBack: () -> Unit,
    onSelectPid: (String) -> Unit,
    onSelectRange: (TelemetryRange) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                Text("TELEMETRY", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                // Balances the back button so the title stays centred. Kept as
                // an empty spacer rather than a third control: nothing else
                // belongs in this bar (see the file doc on why the car is not
                // switchable from here).
                Text("", style = LegionType.stamp, modifier = Modifier.padding(horizontal = 12.dp))
            }
            Hairline()

            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )

                state.totalCount == 0 -> {
                    SectionHeader(state.carLabel.ifBlank { "THIS CAR" })
                    Text(
                        "No telemetry recorded for this car. The recorder writes a sample every 30 seconds while an OBD adapter is connected and the engine is running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.faint,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                else -> {
                    SectionHeader(
                        state.carLabel.ifBlank { "THIS CAR" },
                        "${groupThousands(state.totalCount)} readings",
                    )
                    spanLine(state.firstSampleMs, state.lastSampleMs)?.let { span ->
                        Text(
                            span,
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        state.pids.forEach { pid ->
                            PidChip(pid, selected = pid == state.selectedPid, onSelect = { onSelectPid(pid) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TelemetryRange.entries.forEach { option ->
                            RangeChip(option, selected = option == state.range, onSelect = { onSelectRange(option) })
                        }
                    }
                    Hairline()

                    val summary = summaryLine(state.summary, state.unit)
                    if (summary == null) {
                        Text(
                            "No ${state.selectedPid?.let { ObdHistory.pidLabel(it) }.orEmpty().lowercase()} readings in this range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    } else {
                        Text(
                            summary,
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        TelemetryChart(state.series?.points.orEmpty())
                        // Stated, never implied: the line stops early because
                        // the query capped, not because the car stopped.
                        if (state.series?.truncated == true) {
                            Text(
                                "Chart shows the newest $ROW_CAP readings in this range. The figures above cover all of it.",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        // What the RANGE actually covers, as opposed to what
                        // the car has recorded all-time in the header above -
                        // "1Y" on a car parked since March covers a lot less
                        // than a year, and the row says which.
                        state.summary?.let { s ->
                            Hairline()
                            spanLine(s.firstMs, s.lastMs)?.let { windowSpan ->
                                ReadingRow("In range", windowSpan, sub = "${groupThousands(s.count)} readings")
                                Hairline()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

private fun previewSeries(): TelemetrySeries = TelemetrySeries(
    points = (0 until 90).map { i ->
        (1_753_000_000_000L + i * 30_000L) to (900.0 + 800 * kotlin.math.sin(i / 7.0))
    },
    unit = "rpm",
    truncated = false,
)

@Preview(name = "Telemetry: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewTelemetryLoading() = LegionTheme {
    TelemetryContent(TelemetryUiState(loading = true), onBack = {}, onSelectPid = {}, onSelectRange = {})
}

@Preview(name = "Telemetry: the imported Outlander", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewTelemetryPopulated() = LegionTheme {
    TelemetryContent(
        TelemetryUiState(
            loading = false,
            carLabel = "2020 Mitsubishi Outlander",
            totalCount = 5_242,
            firstSampleMs = 1_720_000_000_000L,
            lastSampleMs = 1_753_000_000_000L,
            pids = listOf("010C", "010D", "0105", "0107", "ATRV"),
            selectedPid = "010C",
            range = TelemetryRange.ALL,
            series = previewSeries(),
            summary = PidSummary(
                min = 612.0, max = 3410.0, avg = 1847.4, count = 5242,
                firstMs = 1_720_000_000_000L, lastMs = 1_753_000_000_000L,
            ),
            unit = "rpm",
        ),
        onBack = {}, onSelectPid = {}, onSelectRange = {},
    )
}

@Preview(name = "Telemetry: PID with nothing in this range", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewTelemetryEmptyRange() = LegionTheme {
    TelemetryContent(
        TelemetryUiState(
            loading = false,
            carLabel = "2020 Mitsubishi Outlander",
            totalCount = 5_242,
            firstSampleMs = 1_720_000_000_000L,
            lastSampleMs = 1_753_000_000_000L,
            pids = listOf("010C", "0105"),
            selectedPid = "0105",
            range = TelemetryRange.WEEK,
            series = TelemetrySeries(emptyList(), "°C", truncated = false),
            summary = PidSummary(0.0, 0.0, 0.0, 0, 0L, 0L),
            unit = "°C",
        ),
        onBack = {}, onSelectPid = {}, onSelectRange = {},
    )
}

@Preview(name = "Telemetry: a car with no history at all", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewTelemetryNone() = LegionTheme {
    TelemetryContent(
        TelemetryUiState(loading = false, carLabel = "this car", totalCount = 0),
        onBack = {}, onSelectPid = {}, onSelectRange = {},
    )
}
