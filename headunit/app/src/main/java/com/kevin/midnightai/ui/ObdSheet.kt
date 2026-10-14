package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.kevin.midnightai.vehicle.ActiveVehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.PidSummary
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.util.shortDate
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdGauge
import com.kevin.midnightai.vehicle.ObdHistory
import com.kevin.midnightai.vehicle.VehicleController
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Which section of the unified OBD sheet is showing. */
internal enum class ObdSheetTab { LIVE, HISTORY, CODES, MANAGE }

/**
 * The unified OBD sheet (B30): LIVE readouts, retained HISTORY (the moat table nobody could
 * previously see - [com.kevin.midnightai.data.local.OdbSampleDao] had zero UI callers before
 * this), the DTC reader (CODES, wraps [DtcContent]), and MANAGE (pairing + the gauge picker).
 * It absorbed the old DTC sheet's Cruise entry point (the standalone DtcSheet wrapper
 * was deleted 2026-10-14; the reader body is [DtcContent]).
 *
 * Every tab is open to every install. HISTORY used to hide all but the latest drive behind the
 * $10 unlock (a value-capture gate, not a serving cost); the commercial model is dead (root ADR
 * 0004) and that gate was removed 2026-10-14, so HISTORY shows the full retained drive list.
 */
@Composable
internal fun ObdSheet(context: Context, onDismiss: () -> Unit, initialTab: ObdSheetTab = ObdSheetTab.LIVE) {
    var tab by remember { mutableStateOf(initialTab) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background.copy(alpha = 0.97f)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Header: title + close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(14.dp).background(AriaColors.StampRed))
                Spacer(Modifier.width(8.dp))
                Text("OBD", style = AriaType.neonLabel.copy(fontSize = 13.sp), color = AriaColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                SheetAction("CLOSE") { onDismiss() }
            }
            Spacer(Modifier.height(10.dp))

            // Tab row.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in ObdSheetTab.entries) {
                    ObdTabChip(label = t.name, selected = tab == t, onClick = { tab = t })
                }
            }
            Spacer(Modifier.height(14.dp))

            when (tab) {
                ObdSheetTab.LIVE -> LiveTab(selected = tab == ObdSheetTab.LIVE)
                ObdSheetTab.HISTORY -> HistoryTab(context)
                ObdSheetTab.CODES -> DtcContent(context = context, onDismiss = onDismiss)
                ObdSheetTab.MANAGE -> ManageTab(context)
            }
        }
    }
}

@Composable
private fun ObdTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel.copy(fontSize = 11.sp),
        color = if (selected) AriaColors.Background else AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) AriaColors.Amber else Color.Transparent)
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel,
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// LIVE
// ---------------------------------------------------------------------------

/**
 * Live gauge readout, every supported slow-changing PID (see [ObdGauge]'s own kdoc for why fast
 * PIDs like RPM aren't offered). Polls at a 15s cadence, ONLY while this tab is selected and the
 * sheet is open - the head unit has one Bluetooth radio shared with A2DP music; a second, faster
 * poll loop here would starve it exactly like RPM did on Lights Out (removed 2026-07-12). The
 * [LaunchedEffect] is keyed on [selected] and cancels automatically when the tab changes or the
 * sheet is dismissed, so there's never more than one loop running.
 */
@Composable
private fun LiveTab(selected: Boolean) {
    val supported by ObdBluetoothManager.supportedPids.collectAsState()
    val gauges = ObdGauge.entries.filter { it.isSupported(supported) }
    var readings by remember { mutableStateOf<Map<ObdGauge, String?>>(emptyMap()) }

    LaunchedEffect(selected, supported) {
        if (!selected) return@LaunchedEffect
        while (true) {
            readings = gauges.associateWith { runCatching { it.read() }.getOrNull() }
            delay(15_000L)
        }
    }

    if (!ObdBluetoothManager.isConnected) {
        CenterNote("OBD NOT CONNECTED", sub = "Plug the adapter in to see live readings.")
        return
    }
    if (gauges.isEmpty()) {
        CenterNote("NO SLOW-CHANGING PIDS SUPPORTED", sub = "This car didn't report any of the gauges we poll.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(gauges, key = { it.key }) { gauge ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AriaColors.Panel.copy(alpha = 0.8f))
                    .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(gaugeDisplayName(gauge), style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
                Text(
                    readings[gauge] ?: "-",
                    style = AriaType.readout.copy(fontSize = 18.sp),
                    color = AriaColors.Amber,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------------

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
private fun fmtTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(com.kevin.midnightai.time.TrustedClock.zone()).format(TIME_FMT)

private data class HistoryState(
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val firstSampleMs: Long? = null,
    val drives: List<ObdHistory.DriveWindow> = emptyList(),
)

/**
 * The B30 payload: everything obd_samples has been quietly recording for a year, now reachable.
 * Every drive in the retained window is listed, newest first, with every PID chart. (Until
 * 2026-10-14 only the latest drive was shown without the $10 unlock; that gate is gone.)
 */
@Composable
private fun HistoryTab(context: Context) {
    var state by remember { mutableStateOf(HistoryState()) }
    var selectedDrive by remember { mutableStateOf<ObdHistory.DriveWindow?>(null) }
    var movingDrive by remember { mutableStateOf<ObdHistory.DriveWindow?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val vehicle = VehicleController.currentVehicle(context)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val total = dao.totalCount(vehicle.obdMac)
        val first = dao.firstSampleMs(vehicle.obdMac)
        // Enough timestamps to find every drive boundary in the retained window without loading
        // every sample's value - 200k timestamps (Longs only) is a few MB, comfortably safe on a
        // cheap head unit even across the full 365-day retention at 30s cadence.
        val timestamps = dao.recentTimestamps(vehicle.obdMac, limit = 200_000)
        val drives = ObdHistory.splitDrives(timestamps)
        state = HistoryState(loading = false, totalCount = total, firstSampleMs = first, drives = drives)
        selectedDrive = drives.firstOrNull()
    }

    when {
        state.loading -> CenterNote("READING THE LOG…")
        state.totalCount == 0 -> CenterNote(
            "NO HISTORY YET",
            sub = "Telemetry records every 30s once the engine's running and the adapter's connected.",
        )
        else -> {
            val visibleDrives = state.drives
            Column(Modifier.fillMaxSize()) {
                Text(
                    state.firstSampleMs?.let { "${formatCount(state.totalCount)} readings since ${shortDate(it)}" }
                        ?: "${formatCount(state.totalCount)} readings",
                    style = AriaType.readout.copy(fontSize = 13.sp),
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                // Drive picker: newest first. weight(1f) gives this Row (and the LazyColumns
                // inside it) the Column's remaining bounded height - an unbounded LazyColumn
                // inside an unbounded Row crashes on measure.
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only worth showing a picker list when there's more than one drive to pick
                    // from - a single drive gets straight to the data.
                    if (visibleDrives.size > 1) {
                        LazyColumn(
                            modifier = Modifier.width(150.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleDrives, key = { it.fromMs }) { drive ->
                                val active = drive == selectedDrive
                                Text(
                                    shortDate(drive.toMs) + "\n" + fmtTime(drive.fromMs).substringAfter(", "),
                                    style = AriaType.readout.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                    color = if (active) AriaColors.Background else AriaColors.TextPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) AriaColors.Amber else AriaColors.Panel.copy(alpha = 0.6f))
                                        .clickable { selectedDrive = drive }
                                        .padding(8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    val drive = selectedDrive
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        if (drive != null) {
                            DriveDetail(context = context, drive = drive, modifier = Modifier.weight(1f))
                            // The corrector (car manager, 2026-07-16). Lives here, not
                            // on CARS: the drive list, selection and charts already
                            // exist here, and CARS manages cars while HISTORY manages
                            // drives. (It was paid-only until the 2026-10-14 removal of
                            // the unlock gate.)
                            TextButton(onClick = { movingDrive = drive }) {
                                Text("MOVE TO ANOTHER CAR", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    movingDrive?.let { drive ->
        MoveDriveDialog(
            context = context,
            drive = drive,
            onDismiss = { movingDrive = null },
            onMoved = { movingDrive = null; reload++ },
        )
    }
}

/**
 * Reassigns one drive to another car (car manager, 2026-07-16).
 *
 * Writes a [DriveReassignment] RULE and applies it locally at once. It is not a
 * plain UPDATE because obd_samples syncs UNION on an identity that includes
 * vehicleId - a direct re-key would clone the drive onto both cars on the next
 * sync rather than move it. See [DriveReassignment]'s doc.
 */
@Composable
private fun MoveDriveDialog(
    context: Context,
    drive: ObdHistory.DriveWindow,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cars by remember { mutableStateOf<List<com.kevin.midnightai.data.local.Vehicle>>(emptyList()) }
    val currentId = remember { ActiveVehicle.current(context) }

    LaunchedEffect(Unit) {
        cars = withContext(Dispatchers.IO) {
            VehicleController.allVehicles(context).filter { it.obdMac != currentId }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MOVE DRIVE") },
        text = {
            Column {
                Text(
                    "${shortDate(drive.fromMs)} - move this drive to:",
                    style = MaterialTheme.typography.bodySmall,
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                if (cars.isEmpty()) {
                    Text(
                        "No other cars yet. Add one on the CARS screen first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AriaColors.TextDim,
                    )
                }
                for (car in cars) {
                    val label = VehicleController.displayLabel(car).ifBlank { car.name }
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        VehicleController.reassignDrive(
                                            context = context,
                                            fromVehicleId = currentId,
                                            toVehicleId = car.obdMac,
                                            fromMs = drive.fromMs,
                                            toMs = drive.toMs,
                                        )
                                    }
                                    onMoved()
                                }
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

private fun formatCount(n: Int): String {
    val s = n.toString()
    val sb = StringBuilder()
    for ((i, c) in s.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.reverse().toString()
}

private data class PidHistory(val pid: String, val summary: PidSummary, val points: List<Pair<Long, Double>>)

private data class DriveDetailState(
    val pidHistories: List<PidHistory> = emptyList(),
    val codeEvents: List<com.kevin.midnightai.data.local.CodeEvent> = emptyList(),
    val loading: Boolean = true
)

/** Per-PID summaries + charts for one drive window. Reloads whenever [drive] changes. */
@Composable
private fun DriveDetail(context: Context, drive: ObdHistory.DriveWindow, modifier: Modifier = Modifier) {
    var state by remember(drive) { mutableStateOf(DriveDetailState()) }

    LaunchedEffect(drive) {
        val vehicle = VehicleController.currentVehicle(context)
        val db = CarDatabase.getDatabase(context)
        val pids = db.odbSampleDao().recordedPids(vehicle.obdMac)
        
        val pidHistories = mutableListOf<PidHistory>()
        for (pid in pids) {
            val summary = db.odbSampleDao().summarize(vehicle.obdMac, pid, drive.fromMs, drive.toMs) ?: continue
            if (summary.count == 0) continue
            val raw = db.odbSampleDao().getRange(vehicle.obdMac, pid, drive.fromMs, drive.toMs)
            val points = ObdHistory.downsample(raw.map { it.timestamp to it.value })
            pidHistories.add(PidHistory(pid, summary, points))
        }

        val codeEvents = db.codeEventDao().getInRange(vehicle.obdMac, drive.fromMs, drive.toMs)
        
        state = DriveDetailState(pidHistories, codeEvents, loading = false)
    }

    if (state.loading) {
        CenterNote("LOADING DRIVE…")
        return
    }
    if (state.pidHistories.isEmpty()) {
        CenterNote("NO PIDS RECORDED FOR THIS DRIVE")
        return
    }

    val codeTimestamps = state.codeEvents.map { it.timestamp }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.codeEvents.isNotEmpty()) {
            item { CodeSummaryCard(state.codeEvents) }
        }
        items(state.pidHistories, key = { it.pid }) { h -> 
            PidHistoryCard(h, codeTimestamps) 
        }
    }
}

@Composable
private fun CodeSummaryCard(events: List<com.kevin.midnightai.data.local.CodeEvent>) {
    val allCodes = events.flatMap { 
        try {
            val arr = org.json.JSONArray(it.codesJson)
            (0 until arr.length()).map { i -> arr.getString(i) }
        } catch (e: Exception) { emptyList<String>() }
    }.distinct()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.ScanRed.copy(alpha = 0.15f))
            .border(1.dp, AriaColors.ScanRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("CODES SEEN", style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.ScanRed)
        Spacer(Modifier.height(6.dp))
        Text(
            allCodes.joinToString(", "),
            style = AriaType.readout.copy(fontSize = 14.sp),
            color = AriaColors.TextPrimary
        )
        Text(
            "${events.size} event(s) recorded during this drive.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PidHistoryCard(history: PidHistory, codeTimestamps: List<Long>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Panel.copy(alpha = 0.8f))
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(ObdHistory.pidLabel(history.pid), style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "min %.1f  max %.1f  avg %.1f  (%d samples)".format(
                history.summary.min, history.summary.max, history.summary.avg, history.summary.count,
            ),
            style = AriaType.readout.copy(fontSize = 12.sp),
            color = AriaColors.TextDim,
        )
        // Static line chart, no animation at all (CLAUDE.md sec 9's motion ban) - Canvas draws
        // once per recomposition, there is no tween/AnimatedVisibility/infiniteTransition here.
        if (history.points.size >= 2) {
            Spacer(Modifier.height(8.dp))
            LineChart(history.points, codeTimestamps, modifier = Modifier.fillMaxWidth().height(60.dp))
        }
    }
}

@Composable
private fun LineChart(
    points: List<Pair<Long, Double>>, 
    codeTimestamps: List<Long>,
    modifier: Modifier = Modifier
) {
    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    val minT = points.first().first
    val maxT = points.last().first
    val spanT = (maxT - minT).takeIf { it > 0L } ?: 1L
    val lineColor = AriaColors.Amber

    Canvas(modifier = modifier) {
        // Draw code markers first (background layer)
        codeTimestamps.forEach { ts ->
            if (ts in minT..maxT) {
                val x = size.width * (ts - minT).toFloat() / spanT.toFloat()
                drawLine(
                    color = AriaColors.ScanRed.copy(alpha = 0.6f),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            val x = size.width * (t - minT).toFloat() / spanT.toFloat()
            val y = size.height * (1f - ((v - minV) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

// ---------------------------------------------------------------------------
// MANAGE
// ---------------------------------------------------------------------------

/** Connection status, pairing entry, and both gauge pickers (Cruise strip + Lights Out footer). */
@Composable
private fun ManageTab(context: Context) {
    var showDevices by remember { mutableStateOf(false) }

    if (showDevices) {
        ObdDeviceScreen(onBack = { showDevices = false })
        return
    }

    val connectionState by ObdBluetoothManager.connectionState.collectAsState()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (connectionState) {
                        ObdBluetoothManager.ConnectionState.CONNECTED -> "CONNECTED"
                        ObdBluetoothManager.ConnectionState.CONNECTING -> "CONNECTING…"
                        ObdBluetoothManager.ConnectionState.ERROR -> "CONNECTION ERROR"
                        ObdBluetoothManager.ConnectionState.DISCONNECTED -> "DISCONNECTED"
                    },
                    style = AriaType.neonLabel.copy(fontSize = 12.sp),
                    color = if (connectionState == ObdBluetoothManager.ConnectionState.CONNECTED) {
                        AriaColors.Patina
                    } else {
                        AriaColors.TextDim
                    },
                )
                Spacer(Modifier.weight(1f))
                SheetAction("PAIR / SCAN") { showDevices = true }
            }
        }
        item {
            Text("Cruise gauges", style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
        }
        item { GaugePickerSection(context, GaugeSurface.CRUISE) }
        item {
            Text("Lights Out gauges", style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
        }
        item { GaugePickerSection(context, GaugeSurface.LIGHTS_OUT) }
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

@Composable
private fun CenterNote(text: String, sub: String? = null, color: Color = AriaColors.TextDim) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = AriaType.neonLabel.copy(fontSize = 13.sp), color = color)
        sub?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = AriaType.readout.copy(fontSize = 13.sp), color = AriaColors.TextDim)
        }
    }
}
