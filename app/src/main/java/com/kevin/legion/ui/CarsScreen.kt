package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.fleet.AddCarDialog
import com.kevin.legion.ui.fleet.AutoCarRow
import com.kevin.legion.ui.fleet.CarRow
import com.kevin.legion.ui.fleet.CarRowView
import com.kevin.legion.ui.fleet.CarTelemetry
import com.kevin.legion.ui.fleet.RenameCarDialog
import com.kevin.legion.ui.fleet.buildCarRows
import com.kevin.legion.ui.fleet.carLabel
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DongleCarController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.launch
import com.kevin.legion.ui.fleet.FleetSoftSurface
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.soft.AreaAccent

/**
 * `fleet/cars` - the car roster and the explicit picker
 * [com.kevin.legion.vehicle.ActiveVehicle] has documented since the port but
 * that nothing ever built (see `ui/fleet/CarRows.kt`'s file doc for why that
 * mattered once the Midnight AI import landed three cars in the database).
 *
 * **Switching is two calls, in order**, and both live here because
 * [ActiveVehicle] deliberately does not pair them itself - the dongle-driven
 * auto path in [ObdBluetoothManager] needs the second without the first:
 * 1. [ActiveVehicle.select] - write the device-local choice.
 * 2. [ActiveVehicle.notifyResolutionChanged] - tell [com.kevin.legion.ai.AriaBrain]
 *    its cached base instruction is stale and tell a warm Live socket the car
 *    changed. Skipping it is the drive-notes ticket 02 bug: the companion keeps
 *    the previous car's persona and voice until a 2-minute TTL lapses.
 *
 * **Archive, never delete** (the `vehicles` table's own `archived` doc): a car's
 * telemetry shards by month across ALL cars in Drive, so there is no per-car
 * file to remove and a local delete is resurrected by the next UNION merge.
 * Archiving hides it from this roster and rides the ordinary LWW path to the
 * other phone; `SHOW ARCHIVED` is what keeps it reversible.
 *
 * Split per `compose-state-holder-ui-split`, same shape as [CompanionsScreen]:
 * [CarsScreen] owns the loads and every write, [CarsContent] is plain state
 * plus callbacks and is what the `@Preview`s exercise.
 */
data class CarsUiState(
    val loading: Boolean = true,
    val rows: List<CarRowView> = emptyList(),
    /** True when no explicit choice is stored - [ActiveVehicle.current] follows the dongle. */
    val isAuto: Boolean = true,
    /** The car auto resolves to right now, so picking it is not a blind choice. Null if that car has no row yet. */
    val autoResolvesTo: String? = null,
    val showArchived: Boolean = false,
    val archivedCount: Int = 0,
    /**
     * Every car's [carLabel], archived included regardless of [showArchived] - the manual
     * ADD CAR / RENAME dialogs' duplicate-name check ([com.kevin.legion.ui.fleet.CarManageResolver])
     * needs the full roster, not just what happens to be visible right now, or a rename could
     * collide silently with a car currently hidden by the toggle.
     */
    val allLabels: List<String> = emptyList(),
    /**
     * "Which car is the OBD dongle in?" (OBD VIN attribution, 2026-11-15): the hands path for the
     * same question the notification and the spoken ask put, calling the same controller.
     */
    val dongleQuestions: List<DongleQuestion> = emptyList(),
    /** What the last answer or switch did, in words. */
    val dongleMessage: String? = null,
    /** A VIN conflict waiting for an explicit "replace" (subject id, car id). */
    val replaceOffer: Pair<String, String>? = null,
)

/** One open "which car" question. [subjectId] null is the live connection. */
data class DongleQuestion(
    val subjectId: String?,
    val text: String,
    val canLookUpNewCar: Boolean,
    /** Car id to label, the choices. */
    val cars: List<Pair<String, String>>,
)

@Composable
fun CarsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CarsUiState()) }
    // Bumped after any write commits, to key the reload below - the same shape
    // as CompanionsScreen's reloadNonce.
    var reloadNonce by remember { mutableStateOf(0) }
    var showArchived by remember { mutableStateOf(false) }
    var dongleMessage by remember { mutableStateOf<String?>(null) }
    val dongleCar by DongleCarController.state.collectAsStateWithLifecycle()
    val conflict by DongleCarController.conflict.collectAsStateWithLifecycle()

    LaunchedEffect(reloadNonce, showArchived, dongleCar, conflict, dongleMessage) {
        // Archived rows are loaded ALWAYS and filtered in buildCarRows, so the
        // SHOW ARCHIVED toggle is a pure display flip with no second DB round
        // trip - and so archivedCount can be honest about how many are hidden.
        val vehicles = VehicleController.allVehiclesIncludingArchived(context)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val telemetry = vehicles.associate { vehicle ->
            vehicle.obdMac to CarTelemetry(
                sampleCount = dao.totalCount(vehicle.obdMac),
                lastSampleMs = dao.lastSampleMs(vehicle.obdMac),
            )
        }
        val selectedId = ActiveVehicle.selected(context)
        // What ActiveVehicle.current WOULD resolve to with no explicit choice -
        // deliberately recomputed rather than read from `current`, which would
        // return the driver's own selection and make the auto row describe
        // itself.
        // 2026-11-15: the car the dongle's VIN (or Kevin's answer) named, when there is one.
        val autoId = DongleCar.activeCarId(null, ObdBluetoothManager.connectedDeviceAddress, dongleCar)

        state = CarsUiState(
            loading = false,
            rows = buildCarRows(
                vehicles = vehicles,
                telemetry = telemetry,
                selectedId = selectedId,
                resolvedId = ActiveVehicle.current(context),
                showArchived = showArchived,
            ),
            isAuto = selectedId == null,
            autoResolvesTo = vehicles.firstOrNull { it.obdMac == autoId }?.let { carLabel(it) },
            showArchived = showArchived,
            archivedCount = vehicles.count { it.archived },
            allLabels = vehicles.map { carLabel(it) },
            dongleQuestions = dongleQuestions(context, dongleCar),
            dongleMessage = dongleMessage,
            replaceOffer = conflict?.let { it.subjectId to it.carId },
        )
    }

    fun answered(outcome: DongleCarController.Outcome) {
        dongleMessage = outcome.message
        reloadNonce++
    }

    CarsContent(
        state = state,
        onBack = onBack,
        onActivate = { vehicleId ->
            scope.launch {
                // The picker is the stored-data pick only; "which car is the dongle in" is the OBD
                // dongle card above it (2026-11-15), so a tap here never moves a drive.
                ActiveVehicle.select(context, vehicleId)
                ActiveVehicle.notifyResolutionChanged(context)
                reloadNonce++
            }
        },
        onDongleAnswer = { subjectId, carId ->
            scope.launch { answered(DongleCarController.answer(context, carId, subjectId)) }
        },
        onDongleNewCar = { subjectId ->
            scope.launch { answered(DongleCarController.answerNewCar(context, subjectId)) }
        },
        onDongleReplace = { subjectId, carId ->
            scope.launch { answered(DongleCarController.answer(context, carId, subjectId, replaceVin = true)) }
        },
        onFollowAdapter = {
            scope.launch {
                ActiveVehicle.select(context, null)
                ActiveVehicle.notifyResolutionChanged(context)
                reloadNonce++
            }
        },
        onArchive = { vehicleId ->
            scope.launch {
                VehicleController.archive(context, vehicleId)
                // archive() clears the selection when the archived car was the
                // active one, so the resolution can have changed here too.
                ActiveVehicle.notifyResolutionChanged(context)
                reloadNonce++
            }
        },
        onUnarchive = { vehicleId ->
            scope.launch {
                VehicleController.unarchive(context, vehicleId)
                reloadNonce++
            }
        },
        onToggleArchived = { showArchived = !showArchived },
        onAddCar = { year, make, model, trim, name, engine ->
            scope.launch {
                // Same call the manage_vehicle voice tool's "add" action makes
                // (LiveToolbox.manageVehicle) - no second write path. addVehicle
                // never touches ActiveVehicle.select itself (see its own doc:
                // adding a second car mid-drive must not silently move which
                // car is active), so nothing here needs to re-notify a live
                // session the way onActivate/onArchive do.
                VehicleController.addVehicle(context, year, make, model, trim, name, engine)
                reloadNonce++
            }
        },
        onRename = { vehicleId, newName ->
            scope.launch {
                // Same shape manage_vehicle's "rename" action uses: correctVehicle
                // with ONLY name set, never make/model/year/trim - see
                // RenameCarDialog's own doc for why this is deliberately narrower
                // than a full "correct the badge" edit.
                VehicleController.correctVehicle(context, vehicleId, name = newName)
                reloadNonce++
            }
        },
    )
}

/** Plain UI: [state] plus callbacks, no [VehicleController]/Room reference - see the file doc comment. */
@Composable
fun CarsContent(
    state: CarsUiState,
    onBack: () -> Unit,
    onActivate: (vehicleId: String) -> Unit,
    onFollowAdapter: () -> Unit,
    onArchive: (vehicleId: String) -> Unit,
    onUnarchive: (vehicleId: String) -> Unit,
    onToggleArchived: () -> Unit,
    onAddCar: (year: Int, make: String, model: String, trim: String, name: String, engine: String) -> Unit,
    onRename: (vehicleId: String, newName: String) -> Unit,
    onDongleAnswer: (subjectId: String?, carId: String) -> Unit = { _, _ -> },
    onDongleNewCar: (subjectId: String?) -> Unit = {},
    onDongleReplace: (subjectId: String, carId: String) -> Unit = { _, _ -> },
) {
    // Which dialog is open, if any - plain local UI state, same "ephemeral, not
    // persisted, doesn't belong in CarsUiState" posture as
    // CategoryDrilldownScreen's expandedTxnId. At most one at a time.
    var showAddDialog by remember { mutableStateOf(false) }
    var renamingVehicleId by remember { mutableStateOf<String?>(null) }

    if (showAddDialog) {
        AddCarDialog(
            existingLabels = state.allLabels,
            onDismiss = { showAddDialog = false },
            onAdd = { year, make, model, trim, name, engine ->
                onAddCar(year, make, model, trim, name, engine)
                showAddDialog = false
            },
        )
    }
    val renamingRow = state.rows.firstOrNull { it.vehicleId == renamingVehicleId }
    if (renamingRow != null) {
        RenameCarDialog(
            currentLabel = renamingRow.label,
            otherLabels = state.allLabels.filterNot { it.equals(renamingRow.label, ignoreCase = true) },
            onDismiss = { renamingVehicleId = null },
            onRename = { newName ->
                onRename(renamingRow.vehicleId, newName)
                renamingVehicleId = null
            },
        )
    }

    FleetSoftSurface {
        val sem = LocalLegionSemantics.current
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Cars", onBack = onBack, accent = AreaAccent.FLEET)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showAddDialog = true }) {
                        Text("Add car", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    // Disabled rather than hidden when nothing is archived: a
                    // toggle that appears only once you have archived something is
                    // a toggle nobody discovers before they need it.
                    TextButton(onClick = onToggleArchived, enabled = state.archivedCount > 0) {
                        Text(
                            if (state.showArchived) "Hide archived" else "Show archived",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.archivedCount > 0) MaterialTheme.colorScheme.primary else sem.ghost,
                        )
                    }
                }
            }
            Hairline()

            if (state.loading) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                dongleItems(state, onDongleAnswer, onDongleNewCar, onDongleReplace)
                item(key = "auto-header") { SectionHeader("Selection", if (state.isAuto) "Auto" else "Pinned") }
                item(key = "auto-row") {
                    AutoCarRow(isAuto = state.isAuto, resolvesTo = state.autoResolvesTo, onSelect = onFollowAdapter)
                    Hairline()
                }

                item(key = "cars-header") {
                    SectionHeader(
                        "Cars",
                        if (state.archivedCount > 0 && !state.showArchived) {
                            "${state.rows.size} - ${state.archivedCount} archived"
                        } else {
                            state.rows.size.toString()
                        },
                    )
                }
                if (state.rows.isEmpty()) {
                    item(key = "cars-empty") {
                        Text(
                            "No cars yet. One appears the first time an OBD adapter connects.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(state.rows, key = { it.vehicleId }) { row ->
                        CarRow(
                            row = row,
                            onActivate = { onActivate(row.vehicleId) },
                            onRename = { renamingVehicleId = row.vehicleId },
                            onArchive = { onArchive(row.vehicleId) },
                            onUnarchive = { onUnarchive(row.vehicleId) },
                        )
                        Hairline()
                    }
                }
            }
        }
    }
}

/** The OBD dongle section: what the last answer did (with Replace when one is owed), then each open question. */
private fun LazyListScope.dongleItems(
    state: CarsUiState,
    onAnswer: (subjectId: String?, carId: String) -> Unit,
    onNewCar: (subjectId: String?) -> Unit,
    onReplace: (subjectId: String, carId: String) -> Unit,
) {
    if (state.dongleQuestions.isEmpty() && state.dongleMessage == null) return
    item(key = "dongle-header") { SectionHeader("OBD dongle", "Which car") }
    state.dongleMessage?.let { message ->
        item(key = "dongle-message") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                state.replaceOffer?.let { (subject, car) ->
                    TextButton(onClick = { onReplace(subject, car) }) {
                        Text("Replace the VIN on file", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Hairline()
        }
    }
    items(state.dongleQuestions, key = { "dongle-q-${it.subjectId ?: "live"}" }) { q ->
        DongleQuestionCard(q, onAnswer, onNewCar)
        Hairline()
    }
}

/** One "which car" question: the sentence, a button per car, and "A new car" when a VIN can be looked up. */
@Composable
private fun DongleQuestionCard(
    q: DongleQuestion,
    onAnswer: (subjectId: String?, carId: String) -> Unit,
    onNewCar: (subjectId: String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(q.text, style = MaterialTheme.typography.bodyMedium)
        q.cars.forEach { (carId, label) ->
            TextButton(onClick = { onAnswer(q.subjectId, carId) }) {
                Text("It's the $label", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (q.canLookUpNewCar) {
            TextButton(onClick = { onNewCar(q.subjectId) }) {
                Text("A new car (look up its VIN)", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Text(
                "A new car? Add it above, then pick it here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The open "which car is the dongle in" questions: the live connection's (held, or an assumed car
 * not yet confirmed), then any earlier connection whose readings still wait under a pending id.
 */
private suspend fun dongleQuestions(context: android.content.Context, live: DongleCar?): List<DongleQuestion> {
    val labels = DongleCarController.labels(context)
    val cars = labels.entries.map { it.key to it.value }
    val out = mutableListOf<DongleQuestion>()
    when (live) {
        is DongleCar.Held ->
            out += DongleQuestion(null, DongleCarController.question(live, labels), live.vin != null, cars)
        is DongleCar.OnCar -> {
            val name = labels[live.carId] ?: "car on file"
            when (live.basis) {
                DongleCar.Basis.ASSUMED -> out += DongleQuestion(
                    null,
                    "Recording as the $name (assumed): the dongle could not read this car's VIN. Which car is it in?",
                    canLookUpNewCar = false,
                    cars = cars,
                )
                DongleCar.Basis.ANSWER -> out += DongleQuestion(
                    null,
                    "Recording as the $name, as you said. Wrong car? Pick the right one and this drive moves.",
                    canLookUpNewCar = false,
                    cars = cars.filter { it.first != live.carId },
                )
                DongleCar.Basis.VIN -> Unit
            }
        }
        null -> DongleCarController.heldWindow(context)?.let { (car, _) ->
            // Parked, assumed car not yet confirmed: still answerable by hand.
            out += DongleQuestion(
                car,
                "The last drive was recorded as the ${labels[car] ?: "car you last confirmed"} " +
                    "(assumed, no VIN). Which car was it?",
                canLookUpNewCar = false,
                cars = cars,
            )
        }
    }
    for (id in DongleCarController.openQuestions(context)) {
        if (id == live?.recordingId) continue
        val vin = DongleCar.vinOfPending(id)
        val what = if (vin != null) DongleCar.vinTail(vin) else "an unreadable VIN"
        out += DongleQuestion(
            id,
            "Readings from an earlier connection ($what) are waiting for you to say which car they came from.",
            canLookUpNewCar = vin != null,
            cars = cars,
        )
    }
    return out
}

// ------------------------------------------------------------------------ previews

private val previewRows = listOf(
    CarRowView(
        vehicleId = "imported-mitsubishi-outlander-2020",
        label = "2020 Mitsubishi Outlander",
        sub = "5,242 readings - last Jun 12, 2026",
        active = true, explicit = true, archived = false,
    ),
    CarRowView(
        vehicleId = "default",
        label = "a car you haven't named yet",
        sub = "no telemetry recorded",
        active = false, explicit = false, archived = false,
    ),
)

@Preview(name = "Cars: loading", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCarsLoading() = LegionTheme {
    CarsContent(
        CarsUiState(loading = true),
        onBack = {}, onActivate = {}, onFollowAdapter = {}, onArchive = {}, onUnarchive = {}, onToggleArchived = {},
        onAddCar = { _, _, _, _, _, _ -> }, onRename = { _, _ -> },
    )
}

@Preview(name = "Cars: a pinned car and a placeholder", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCarsPinned() = LegionTheme {
    CarsContent(
        CarsUiState(
            loading = false, rows = previewRows, isAuto = false, autoResolvesTo = "a car you haven't named yet", archivedCount = 1,
            allLabels = previewRows.map { it.label },
        ),
        onBack = {}, onActivate = {}, onFollowAdapter = {}, onArchive = {}, onUnarchive = {}, onToggleArchived = {},
        onAddCar = { _, _, _, _, _, _ -> }, onRename = { _, _ -> },
    )
}

@Preview(name = "Cars: following the adapter, nothing pinned", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCarsAuto() = LegionTheme {
    CarsContent(
        CarsUiState(
            loading = false,
            rows = previewRows.map { it.copy(active = it.vehicleId == "default", explicit = false) },
            isAuto = true,
            autoResolvesTo = "a car you haven't named yet",
            allLabels = previewRows.map { it.label },
        ),
        onBack = {}, onActivate = {}, onFollowAdapter = {}, onArchive = {}, onUnarchive = {}, onToggleArchived = {},
        onAddCar = { _, _, _, _, _, _ -> }, onRename = { _, _ -> },
    )
}

@Preview(name = "Cars: empty roster", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCarsEmpty() = LegionTheme {
    CarsContent(
        CarsUiState(loading = false, rows = emptyList(), isAuto = true),
        onBack = {}, onActivate = {}, onFollowAdapter = {}, onArchive = {}, onUnarchive = {}, onToggleArchived = {},
        onAddCar = { _, _, _, _, _, _ -> }, onRename = { _, _ -> },
    )
}
