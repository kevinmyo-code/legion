package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.diag.ClearPlan
import com.kevin.legion.vehicle.diag.ClearPlanner
import com.kevin.legion.vehicle.diag.DiagScanController
import com.kevin.legion.vehicle.diag.DiagScanStore
import com.kevin.legion.vehicle.diag.DiagVoice
import com.kevin.legion.vehicle.diag.ModuleReport
import com.kevin.legion.vehicle.diag.ScanCompare
import com.kevin.legion.vehicle.diag.ScanWords
import com.kevin.legion.vehicle.diag.StoredScan
import kotlinx.coroutines.launch

/** What the screen shows: the car, its two newest scans, and the last clear's words. */
private data class ScanScreenData(val carId: String, val carLabel: String, val scans: List<StoredScan>)

/**
 * Every module's codes, by hand (ADR 0035's hands path for "scan the truck" and "clear the codes in
 * the ABS"). The same controller as voice: [DiagScanController] scans and clears, [ScanWords]
 * words both, [ClearPlanner] decides what may be cleared. Clearing goes through a dialog that names
 * the car and every module, and leaves the airbag module and unnamed modules out, in words.
 */
@Composable
fun ModuleScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DiagScanController.state.collectAsState()
    var data by remember { mutableStateOf<ScanScreenData?>(null) }
    var clearWords by remember { mutableStateOf<String?>(null) }
    var askClear by remember { mutableStateOf(false) }
    val doneId = (state as? DiagScanController.State.Done)?.scanId
    LaunchedEffect(doneId, clearWords) {
        val carId = ActiveVehicle.recordingId(context)
        data = ScanScreenData(carId, DiagVoice.carLabel(context, carId), DiagScanStore.latest(context, carId, 2))
    }
    val running = state is DiagScanController.State.Running
    FleetSoftSurface {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Every module", onBack = onBack, accent = AreaAccent.FLEET)
            Hairline()
            ScanControls(
                state = state,
                onScan = { full ->
                    clearWords = null
                    if (DiagScanController.start(context, full) == DiagScanController.Start.NOT_CONNECTED) {
                        clearWords = DiagScanController.NOT_CONNECTED
                    }
                },
                onClear = { askClear = true },
                canClear = !running && data?.scans?.isNotEmpty() == true,
            )
            clearWords?.let { Note(it) }
            val d = data
            if (d == null || d.scans.isEmpty()) {
                Note("No module scan of the ${d?.carLabel ?: "car"} yet. Key at ON, then scan.")
            } else {
                ScanBody(d)
            }
        }
        val d = data
        if (askClear && d != null && d.scans.isNotEmpty()) {
            ClearDialog(
                carLabel = d.carLabel,
                plan = ClearPlanner.plan(d.scans.first().report),
                onDismiss = { askClear = false },
                onConfirm = { chosen ->
                    askClear = false
                    scope.launch {
                        val run = DiagScanController.clear(context, d.scans.first().row.id, chosen)
                        clearWords = run?.let { ScanWords.clearSummary(d.carLabel, it) }
                            ?: DiagScanController.NOT_CONNECTED
                    }
                },
            )
        }
    }
}

@Composable
private fun ScanControls(
    state: DiagScanController.State,
    onScan: (Boolean) -> Unit,
    onClear: () -> Unit,
    canClear: Boolean,
) {
    val running = state as? DiagScanController.State.Running
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckButton(text = "Quick scan", onClick = { onScan(false) }, enabled = running == null)
        DeckButton(text = "Full sweep", onClick = { onScan(true) }, enabled = running == null)
        DeckButton(text = "Clear codes", onClick = onClear, enabled = canClear, destructive = true)
    }
    when {
        running != null -> Note(
            "Scanning" + running.progress
                ?.let { " ${it.probed} of ${it.planned} addresses, ${it.found} modules so far" }
                .orEmpty() + ". The OBD gauges pause until it ends.",
        )
        state is DiagScanController.State.Failed -> Note(state.words)
        else -> Note(READ_ONLY_NOTE)
    }
}

@Composable
private fun ScanBody(d: ScanScreenData) {
    val newest = d.scans.first()
    val diff = d.scans.getOrNull(1)?.let { ScanCompare.diff(it.report, newest.report) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { Note(ScanWords.summary(newest.report)) }
        diff?.let { item { Note(compareWords(it)) } }
        items(newest.report.modules, key = { it.address }) { m ->
            ModuleRow(m) { code -> DiagScanStore.describe(context, code).words }
            Hairline()
        }
        if (newest.report.readings.isNotEmpty()) {
            item { Note("Extra readings (unverified: from community lists, not checked on this car)") }
            items(newest.report.readings, key = { it.key }) { r ->
                Note("${r.label}: ${r.value ?: r.outcome.words} ${r.unit}  (unverified)")
            }
        }
        item { Note("Adapter: ${newest.report.adapter.words}. Bus: ${newest.report.bus.words}.") }
        newest.report.notes.forEach { n -> item { Note(n) } }
    }
}

private fun compareWords(d: com.kevin.legion.vehicle.diag.ScanDiff): String = buildList {
    add("Since the scan before: new ${d.added.ifEmpty { listOf("none") }.joinToString()}")
    add("gone ${d.gone.ifEmpty { listOf("none") }.joinToString()}")
    add("unchanged ${d.unchanged.size}")
    if (d.notRechecked.isNotEmpty()) {
        add("not rechecked (module did not answer this time) ${d.notRechecked.joinToString()}")
    }
}.joinToString("; ") + "."

@Composable
private fun ModuleRow(m: ModuleReport, meaning: (String) -> String) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(ScanWords.label(m), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (m.restraint) Text("Airbag / restraint module: read only, never cleared here", color = sem.faint)
        val faults = m.codes.dtcs.filter { it.fault }
        val status = when {
            m.codes.outcome != com.kevin.legion.vehicle.diag.Outcome.READ ->
                "Codes ${m.codes.outcome.words}" + (m.codes.detail?.let { ": $it" } ?: "") + ". Not known to be clear."
            faults.isEmpty() -> "No fault codes (${m.codes.service})"
            else -> "${faults.size} fault code(s) (${m.codes.service})"
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        faults.forEach { c ->
            Text(
                "${c.code}: ${meaning(c.code)} [${c.words.joinToString(", ")}]",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(m.nameSource ?: "Name: unidentified", style = MaterialTheme.typography.labelSmall, color = sem.faint)
    }
}

@Composable
private fun ClearDialog(carLabel: String, plan: ClearPlan, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var chosen by remember(plan) { mutableStateOf(plan.targets.map { it.address }.toSet()) }
    DeckDialog(title = "Clear codes on the $carLabel", onDismissRequest = onDismiss) {
        if (plan.targets.isEmpty()) Note("Nothing from the last scan can be cleared.")
        plan.targets.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = t.address in chosen,
                    onCheckedChange = { on -> chosen = if (on) chosen + t.address else chosen - t.address },
                )
                Text(t.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        plan.excluded.forEach { Note("Not cleared: ${it.label}, ${it.reason}.") }
        Note(CLEAR_NOTE)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(text = "Cancel", onClick = onDismiss)
            DeckButton(
                text = "Clear ${chosen.size} on the $carLabel",
                onClick = { onConfirm(chosen) },
                enabled = chosen.isNotEmpty(),
                destructive = true,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val READ_ONLY_NOTE = "Read-only: a scan only asks. Quick checks the usual addresses (under a " +
    "minute); a full sweep asks every address (a few minutes)."
private const val CLEAR_NOTE = "Engine off, key at ON, parked. Clearing wipes the stored codes and freeze data in " +
    "each chosen module; the engine's readiness monitors reset too."
