package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.NotBuiltRow
import com.kevin.legion.ui.common.ReadingRow
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.FaultRow
import com.kevin.legion.ui.fleet.FaultRowView
import com.kevin.legion.ui.fleet.LIVE_GAUGE_PIDS
import com.kevin.legion.ui.fleet.LiveRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.fleet.buildLiveRows
import com.kevin.legion.ui.fleet.distinctFaultsByFirstSeen
import com.kevin.legion.ui.fleet.groupThousands
import com.kevin.legion.ui.theme.LegionSemantics
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kevin.legion.location.PlaceController

/**
 * `fleet` tab. Ticket 09 resolution §1: four blocks, LIVE / DUE / FAULTS /
 * NOT BUILT YET, read-only. **Nothing in the OBD stack has run since the
 * port** - this screen doubles as the way to find out whether it still
 * works, which is why LIVE renders a connection state and per-row
 * last-seen ages rather than implying a live reading it does not have (see
 * [com.kevin.legion.ui.fleet.buildLiveRows]'s doc).
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill, same
 * shape as [LedgerScreen]: [FleetScreen] is the state holder (talks to
 * [VehicleController]/[CarDatabase]/[ObdBluetoothManager], owns every side
 * effect), [FleetContent] is plain UI state plus callbacks and is what the
 * `@Preview`s below exercise.
 *
 * **Read-only.** No fleet interaction is settled by this ticket - [onOpenPlaces]
 * is the one existing action (absorbed from the pre-ticket-09 placeholder),
 * kept as a plain text link rather than dropped, since removing a working
 * nav path is not in scope either.
 */
data class FleetUiState(
    val loading: Boolean = true,
    val vehicleLabel: String = "",
    val mileageText: String = "",
    val connected: Boolean = false,
    val liveRows: List<LiveRowView> = emptyList(),
    val dueRows: List<DueRowView> = emptyList(),
    val faults: List<Pair<FaultRow, String?>> = emptyList(),
    val serviceHistoryCount: Int = 0,
    val buildSheetCount: Int = 0,
    val recapCount: Int = 0,
)

@Composable
fun FleetScreen(onOpenPlaces: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(FleetUiState()) }
    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val items: List<MaintenanceItem> = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val codeEvents = db.codeEventDao().getAll(vehicle.obdMac)
        val samplesByPid = LIVE_GAUGE_PIDS.associateWith { pid ->
            db.odbSampleDao().getLatest(vehicle.obdMac, pid, 1).firstOrNull()
        }

        // File IO (DtcDescriptions reads a bundled asset + a per-install disk
        // cache), off the composition's dispatcher so a cold read never jank
        // the frame this LaunchedEffect resumes on.
        val descriptions = withContext(Dispatchers.IO) {
            DtcDescriptions.loadSeed(context) + DtcDescriptions.loadLearned(context)
        }
        val faults = distinctFaultsByFirstSeen(codeEvents).map { it to descriptions[it.code]?.first }

        state = FleetUiState(
            loading = false,
            vehicleLabel = VehicleController.displayLabel(vehicle).ifBlank { "This car" },
            mileageText = if (currentMileage > 0) "${groupThousands(currentMileage)} mi" else "",
            connected = ObdBluetoothManager.isConnected,
            liveRows = buildLiveRows(samplesByPid, now),
            dueRows = buildDueRows(items, currentMileage, now),
            faults = faults,
            serviceHistoryCount = db.serviceRecordDao().countForVehicle(vehicle.obdMac),
            buildSheetCount = db.buildEntryDao().countForVehicle(vehicle.obdMac),
            recapCount = db.monthlyRecapDao().getAll(vehicle.obdMac).size,
        )
    }

    // connectionState is the live signal (collected reactively); everything
    // else in `state` is a one-shot DB snapshot loaded above - same "merge a
    // live flow onto an async-loaded state" shape as LedgerScreen's fullState.
    val fullState = state.copy(connected = connectionState == ObdBluetoothManager.ConnectionState.CONNECTED)

    FleetContent(state = fullState, onOpenPlaces = onOpenPlaces)
}

/** Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment. */
@Composable
fun FleetContent(state: FleetUiState, onOpenPlaces: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("FLEET", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onOpenPlaces) {
                    Text("SAVED PLACES", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (state.loading) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            } else {
                FleetListing(state, sem)
            }
        }
    }
}

@Composable
private fun FleetListing(state: FleetUiState, sem: LegionSemantics) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "vehicle-header") {
            SectionHeader(state.vehicleLabel, state.mileageText.ifBlank { null })
        }

        item(key = "live-header") { SectionHeader("LIVE", if (state.connected) "LIVE" else "DISCONNECTED") }
        if (!state.connected) {
            item(key = "live-disconnected-note") {
                Text(
                    "No OBD adapter connected. Values below are the last seen reading.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (state.liveRows.isEmpty()) {
            item(key = "live-empty") {
                Text(
                    "No readings recorded yet. Connect an OBD adapter to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            items(state.liveRows, key = { "live-${it.label}" }) { row ->
                ReadingRow(row.label, row.value, row.sub)
                Hairline()
            }
        }

        item(key = "due-spacer") { Spacer(Modifier.height(14.dp)) }
        item(key = "due-header") { SectionHeader("DUE", "${state.dueRows.size} items") }
        if (state.dueRows.isEmpty()) {
            item(key = "due-empty") {
                Text(
                    "No maintenance schedule yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            items(state.dueRows, key = { "due-${it.label}" }) { row ->
                ReadingRow(row.label, row.value, row.sub, if (row.overdue) sem.quarantined else null)
                Hairline()
            }
        }

        item(key = "faults-spacer") { Spacer(Modifier.height(14.dp)) }
        item(key = "faults-header") { SectionHeader("FAULTS", "${state.faults.size} stored") }
        if (state.faults.isEmpty()) {
            item(key = "faults-empty") {
                Text(
                    "No stored codes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            items(state.faults, key = { (fault, _) -> "fault-${fault.code}-${fault.firstSeenMs}" }) { (fault, description) ->
                FaultRowView(fault, description)
                Hairline()
            }
        }

        item(key = "notbuilt-spacer") { Spacer(Modifier.height(14.dp)) }
        item(key = "notbuilt-header") { SectionHeader("NOT BUILT YET") }
        item(key = "notbuilt-service") {
            NotBuiltRow("Service history", "${state.serviceHistoryCount} records in the database, no screen")
            Hairline()
        }
        item(key = "notbuilt-build") {
            NotBuiltRow("Build sheet", "${state.buildSheetCount} entries, no screen")
            Hairline()
        }
        item(key = "notbuilt-recap") {
            NotBuiltRow("Monthly recaps", "${state.recapCount} generated, never displayed")
        }
        item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Fleet: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewFleetLoading() = LegionTheme {
    FleetContent(FleetUiState(loading = true), onOpenPlaces = {})
}

@Preview(name = "Fleet: disconnected, last-seen readings", widthDp = 360, heightDp = 1200)
@Composable
private fun PreviewFleetDisconnected() = LegionTheme {
    FleetContent(
        FleetUiState(
            loading = false,
            vehicleLabel = "2014 MAZDA 3",
            mileageText = "138,204 mi",
            connected = false,
            liveRows = listOf(
                LiveRowView("Coolant", "88 C", "3 days ago"),
                LiveRowView("Battery", "12.4", "3 days ago"),
                LiveRowView("Fuel trim, long", "-2.3 %", "3 days ago"),
            ),
            dueRows = listOf(
                DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - last at 132,400", overdue = true),
                DueRowView("Front Brake Pads", "in 1750 miles", "every 25,000 mi - last at 115,000", overdue = false),
                DueRowView("Cabin Air Filter", "in 240 days", "every 12 mo - last Dec 4, 2025", overdue = false),
            ),
            faults = listOf(
                FaultRow("P0442", 1_753_000_000_000L) to "EVAP small leak",
                FaultRow("P1101", 1_753_000_000_000L) to null,
            ),
            serviceHistoryCount = 12,
            buildSheetCount = 4,
            recapCount = 3,
        ),
        onOpenPlaces = {},
    )
}

@Preview(name = "Fleet: connected, no history yet", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewFleetConnectedEmpty() = LegionTheme {
    FleetContent(
        FleetUiState(
            loading = false,
            vehicleLabel = "this car",
            mileageText = "",
            connected = true,
            liveRows = emptyList(),
            dueRows = emptyList(),
            faults = emptyList(),
        ),
        onOpenPlaces = {},
    )
}

/**
 * `fleet/places` - absorbed from the deleted `SavedPlacesActivity`. Content
 * unchanged (list of tagged-place labels for the `show_saved_places` voice
 * tool); only the hosting changed, per ticket 07 resolution §5 ("their
 * content is already written - only the hosting changes"). Untouched by
 * ticket 09 - fleet's read-only screens above are additive, not a rewrite of
 * this sub-route.
 */
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var places by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        places = PlaceController.all(context).map { it.label }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TextButton(onClick = onBack) {
                Text("< Back")
            }
            Text(if (places.isEmpty()) "No saved places yet" else places.joinToString("\n"))
        }
    }
}
