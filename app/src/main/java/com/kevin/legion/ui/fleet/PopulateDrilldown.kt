package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.vehicle.PopulateChangeRow
import com.kevin.legion.vehicle.PopulateDiff
import com.kevin.legion.vehicle.PopulatePossibleMatchRow
import com.kevin.legion.vehicle.PopulateRestoreRow
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome
import com.kevin.legion.vehicle.identityPresent
import com.kevin.legion.vehicle.loadPopulateDiff
import kotlinx.coroutines.launch

/**
 * Ticket 14's populate screen (`.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`):
 * the diff-and-confirm UI for "populate the schedule from the factory recommendation." Two entry
 * points reach here, per the ticket's own resolution - a button on `VehicleSpecsScreen` (beside SYNC
 * ID FROM VIN) and one on [FullScheduleScreen] when the schedule is empty - both calling the same
 * `drilldown = FleetDrilldown.POPULATE` in `ui/FleetScreen.kt`.
 *
 * **Not display-only, unlike this package's other drilldowns** (see `FleetDrilldowns.kt`'s own file
 * doc for that convention) - this screen owns its own loads and writes the same way
 * `VehicleSpecsScreen.kt` does, because a populate is a self-contained flow (identity check, an LLM
 * lookup, a diff, per-row accepts) that does not fit `FleetUiState`'s one-shot-snapshot shape without
 * either blocking FLEET's own load on a network call or duplicating the diff state up there for no
 * reader but this screen.
 *
 * **Three steps, only the first two of which are conditional:**
 * 1. If the car has no identity yet ([identityPresent] false), [ManualIdentityForm] first - ticket
 *    14's manual-input field (year/make/model/trim/engine), because the factory-schedule lookup has
 *    nothing to ask about otherwise. Saved through [VehicleController.registerDirect] - the same
 *    insert-or-correct function the voice `register_vehicle` tool already uses, so a car with a row
 *    on file gets a targeted identity write and a car with none yet gets a real insert.
 * 2. If [Vehicle.odometerBaseline] is unset, a note and a button that opens [SetOdometerDialog] -
 *    ticket 14's mileage field, **reusing that exact composable rather than a duplicate text field**
 *    (the ticket's own words: "two implementations would be two places to get the estimate labelling
 *    wrong"). Never blocking - a driver who declines it can still populate; it exists to close "the
 *    exact hole that broke his Jeep" (a fresh car computing due dates against odometer zero), which
 *    is a property of the SCHEDULE going forward, not a precondition the lookup itself needs.
 * 3. [PopulateDiffContent] - [loadPopulateDiff]'s result, one row per candidate, nothing written
 *    until a row's own ACCEPT/DELETE/RESTORE is tapped.
 */
@Composable
fun PopulateScreen(vehicleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var loadedVehicle by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<PopulateDiff?>(null) }
    var loadingDiff by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var identitySaving by remember { mutableStateOf(false) }
    var showOdometerDialog by remember { mutableStateOf(false) }
    // Bumped after an identity save lands, to re-run the load below against the fresh row.
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loadedVehicle = false
        diff = null
        loadError = null
        val v = VehicleController.vehicleFor(context, vehicleId)
        vehicle = v
        loadedVehicle = true
        if (identityPresent(v)) {
            loadingDiff = true
            diff = runCatching { loadPopulateDiff(context, v) }.getOrNull()
            if (diff == null) {
                // Covers BOTH failure shapes deliberately (2026-08-15, ticket 17): the lookup was
                // unreachable, OR it came back with nothing. The old copy named only the connection,
                // which would have read as a lie on the second one. Neither is a statement about the
                // car, and saying "nothing has been changed" out loud matters because the screen the
                // driver would otherwise see proposes deleting their whole schedule.
                loadError = "Couldn't get a factory schedule for this car - either the lookup was " +
                    "unreachable or it came back empty. Nothing has been changed. Try again."
            }
            loadingDiff = false
        }
    }

    val currentVehicle = vehicle
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
                "POPULATE SCHEDULE",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            when {
                !loadedVehicle -> LoadingLine("Loading...")
                currentVehicle != null && !identityPresent(currentVehicle) -> ManualIdentityForm(
                    saving = identitySaving,
                    onSave = { year, make, model, trim, engine ->
                        identitySaving = true
                        scope.launch {
                            VehicleController.registerDirect(context, year, make, model, trim, engine)
                            identitySaving = false
                            reloadKey++
                        }
                    },
                )
                loadingDiff -> LoadingLine("Asking for the factory maintenance schedule - this needs a network connection...")
                loadError != null -> ErrorLine(loadError!!, onRetry = { reloadKey++ })
                diff != null -> Column(Modifier.fillMaxSize()) {
                    if (currentVehicle != null && currentVehicle.odometerBaseline == 0) {
                        MileagePrompt(onSetMileage = { showOdometerDialog = true })
                    }
                    PopulateDiffContent(
                        diff = diff!!,
                        onAccept = { updated -> diff = updated },
                        onAddOutcome = { item -> writePopulateAdd(context, vehicleId, item) },
                        onChangeOutcome = { row -> writePopulateChange(context, vehicleId, row) },
                        onDeleteOutcome = { name -> writePopulateDelete(context, vehicleId, name) },
                        onRestoreOutcome = { row -> writePopulateRestore(context, vehicleId, row) },
                        onMergeMatchOutcome = { row -> writePopulateMergeMatch(context, vehicleId, row) },
                        onAddAsNewOutcome = { row -> writePopulateAddAsNew(context, vehicleId, row) },
                    )
                }
            }
        }
    }

    if (showOdometerDialog && currentVehicle != null) {
        SetOdometerDialog(
            currentValueText = VehicleController.mileageValueText(currentVehicle),
            currentCaveatText = VehicleController.mileageCaveat(currentVehicle).orEmpty(),
            onDismiss = { showOdometerDialog = false },
            onSubmit = { miles ->
                val outcome = VehicleController.setOdometer(context, miles, vehicleId)
                // Refresh ONLY the vehicle row - never [reloadKey], which feeds the
                // LaunchedEffect above and re-runs [loadPopulateDiff] (a fresh LLM call)
                // from scratch (BLOCKING 1a, ticket 14 review fix). This dialog opens
                // mid-populate, with a diff already loaded and possibly partway
                // reviewed; bumping reloadKey here would silently discard and replace
                // that diff under the driver the instant they set their mileage - the
                // exact ordinary sequence ("open populate, set mileage, keep reviewing")
                // the ticket asks this dialog to support. Setting the mileage only needs
                // to clear the mileage banner above the diff, which reads off [vehicle]
                // directly, so re-fetching just that row is sufficient and diff-safe.
                if (outcome.success) vehicle = VehicleController.vehicleFor(context, vehicleId)
                outcome
            },
        )
    }
}

@Composable
private fun LoadingLine(text: String) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Text(text, style = LegionType.stamp, color = sem.faint)
    }
}

@Composable
private fun ErrorLine(text: String, onRetry: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = sem.estimated)
        Spacer(Modifier.height(8.dp))
        DeckButton(text = "TRY AGAIN", onClick = onRetry)
    }
}

@Composable
private fun MileagePrompt(onSetMileage: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "No mileage on file yet - due dates on any new item will compute against an odometer of " +
                "zero until you set one.",
            style = LegionType.stamp,
            color = sem.estimated,
        )
        Spacer(Modifier.height(4.dp))
        DeckButton(text = "SET MILEAGE", onClick = onSetMileage)
    }
}

/**
 * Ticket 14's manual-input field set - year/make/model/trim/engine. Mileage is deliberately NOT
 * here (see [PopulateScreen]'s own doc for why it is [SetOdometerDialog] instead, reused rather than
 * a sixth field on this form).
 */
@Composable
private fun ManualIdentityForm(saving: Boolean, onSave: (year: Int, make: String, model: String, trim: String, engine: String) -> Unit) {
    val sem = LocalLegionSemantics.current
    var yearText by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var trim by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf("") }

    val year = yearText.trim().toIntOrNull() ?: 0
    val canSave = year >= 1900 && make.isNotBlank() && model.isNotBlank() && !saving

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "This car has no year/make/model on file yet, so there's nothing to look a factory " +
                "schedule up for. Fill it in here, or use SYNC ID FROM VIN on the specs screen first.",
            style = MaterialTheme.typography.bodySmall,
            color = sem.faint,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        DeckTextField(value = yearText, onValueChange = { yearText = it }, label = "Year", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(8.dp))
        DeckTextField(value = make, onValueChange = { make = it }, label = "Make")
        Spacer(Modifier.height(8.dp))
        DeckTextField(value = model, onValueChange = { model = it }, label = "Model")
        Spacer(Modifier.height(8.dp))
        DeckTextField(value = trim, onValueChange = { trim = it }, label = "Trim (optional)")
        Spacer(Modifier.height(8.dp))
        // Ticket 14: a 4.0L XJ and a 2.5L XJ differ on plugs and capacities - this is what
        // VehicleController.lookupServiceIntervals folds into the factory-schedule prompt.
        DeckTextField(value = engine, onValueChange = { engine = it }, label = "Engine (optional, e.g. 4.0L I6)")
        Spacer(Modifier.height(16.dp))
        DeckButton(
            text = if (saving) "SAVING..." else "SAVE AND CONTINUE",
            enabled = canSave,
            onClick = { onSave(year, make.trim(), model.trim(), trim.trim(), engine.trim()) },
        )
    }
}

/**
 * The diff itself - five sections, only the non-empty ones rendered, each row carrying its own
 * accept/delete/restore action. Every accept mutates [diff] LOCALLY on success (via [onAccept]) the
 * same way [FullScheduleScreen]'s CONFIRM ALL patches `currentItems` - a just-accepted row must stop
 * showing as pending without waiting for `FleetScreen`'s own reload, which only fires once this
 * screen is actually left.
 *
 * **POSSIBLE MATCH is the fifth section** (ticket 14 review, BLOCKING 1b): a factory name the
 * near-miss comparator ([com.kevin.legion.vehicle.VehicleController.nearMissServiceName]) thinks is
 * probably an existing item under different wording, but is not certain enough of to fold silently
 * into WOULD CHANGE. Two explicit answers, neither a default - see [PossibleMatchRow].
 */
@Composable
private fun PopulateDiffContent(
    diff: PopulateDiff,
    onAccept: (PopulateDiff) -> Unit,
    onAddOutcome: suspend (MaintenanceItem) -> WriteOutcome,
    onChangeOutcome: suspend (PopulateChangeRow) -> WriteOutcome,
    onDeleteOutcome: suspend (String) -> WriteOutcome,
    onRestoreOutcome: suspend (PopulateRestoreRow) -> WriteOutcome,
    onMergeMatchOutcome: suspend (PopulatePossibleMatchRow) -> WriteOutcome,
    onAddAsNewOutcome: suspend (PopulatePossibleMatchRow) -> WriteOutcome,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf<String?>(null) }

    if (diff.isEmpty) {
        Text(
            "Nothing to review - the factory schedule matches what's already on file.",
            style = MaterialTheme.typography.bodySmall,
            color = sem.faint,
            modifier = Modifier.padding(12.dp),
        )
        return
    }

    if (statusText != null) {
        Text(statusText!!, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (diff.wouldAdd.isNotEmpty()) {
            item(key = "add-header") { SectionHeaderCount("WOULD ADD", diff.wouldAdd.size) }
            items(diff.wouldAdd, key = { "add-${it.serviceName}" }) { candidate ->
                WouldAddRow(candidate) {
                    scope.launch {
                        val outcome = onAddOutcome(candidate)
                        statusText = outcome.message
                        if (outcome.success) onAccept(diff.copy(wouldAdd = diff.wouldAdd - candidate))
                    }
                }
            }
        }
        if (diff.wouldChange.isNotEmpty()) {
            item(key = "change-header") { SectionHeaderCount("WOULD CHANGE", diff.wouldChange.size) }
            items(diff.wouldChange, key = { "change-${it.serviceName}" }) { row ->
                WouldChangeRow(row) {
                    scope.launch {
                        val outcome = onChangeOutcome(row)
                        statusText = outcome.message
                        if (outcome.success) onAccept(diff.copy(wouldChange = diff.wouldChange - row))
                    }
                }
            }
        }
        if (diff.possibleMatch.isNotEmpty()) {
            item(key = "possible-header") { SectionHeaderCount("POSSIBLE MATCH - SAME THING?", diff.possibleMatch.size) }
            items(diff.possibleMatch, key = { "possible-${it.factoryName}" }) { row ->
                PossibleMatchRow(
                    row,
                    onSameThing = {
                        scope.launch {
                            val outcome = onMergeMatchOutcome(row)
                            statusText = outcome.message
                            if (outcome.success) onAccept(diff.copy(possibleMatch = diff.possibleMatch - row))
                        }
                    },
                    onAddAsNew = {
                        scope.launch {
                            val outcome = onAddAsNewOutcome(row)
                            statusText = outcome.message
                            if (outcome.success) onAccept(diff.copy(possibleMatch = diff.possibleMatch - row))
                        }
                    },
                )
            }
        }
        if (diff.wouldRestore.isNotEmpty()) {
            item(key = "restore-header") { SectionHeaderCount("YOU DELETED THESE - ADD BACK?", diff.wouldRestore.size) }
            items(diff.wouldRestore, key = { "restore-${it.serviceName}" }) { row ->
                WouldRestoreRow(row) {
                    scope.launch {
                        val outcome = onRestoreOutcome(row)
                        statusText = outcome.message
                        if (outcome.success) onAccept(diff.copy(wouldRestore = diff.wouldRestore - row))
                    }
                }
            }
        }
        if (diff.notInFactorySchedule.isNotEmpty()) {
            // Header softened ticket 18: "NOT IN THE FACTORY SCHEDULE" asserted a fact about the
            // car off a single lookup run that ticket 18 showed disagrees with itself roughly every
            // other run - see NotInScheduleRow's own doc for the full reasoning, reproduced in the
            // per-row copy below.
            item(key = "not-in-header") { SectionHeaderCount("THIS LOOKUP DIDN'T MENTION", diff.notInFactorySchedule.size) }
            items(diff.notInFactorySchedule, key = { "not-in-${it.serviceName}" }) { candidate ->
                NotInScheduleRow(candidate) {
                    scope.launch {
                        val outcome = onDeleteOutcome(candidate.serviceName)
                        statusText = outcome.message
                        if (outcome.success) onAccept(diff.copy(notInFactorySchedule = diff.notInFactorySchedule - candidate))
                    }
                }
            }
        }
        item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeaderCount(label: String, count: Int) {
    val sem = LocalLegionSemantics.current
    Text(
        "$label ($count)",
        style = LegionType.stamp,
        color = sem.faint,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun WouldAddRow(item: MaintenanceItem, onAccept: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DeckRow(label = item.serviceName, value = intervalPhrase(item.intervalMiles, item.intervalMonths))
        Spacer(Modifier.height(4.dp))
        DeckButton(text = "ADD", onClick = onAccept)
    }
}

@Composable
private fun WouldChangeRow(row: PopulateChangeRow, onAccept: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DeckRow(label = row.serviceName, value = intervalPhrase(row.proposedMiles, row.proposedMonths))
        Text(
            // "who authored the current value" (ticket 14's own words) - CONFIRMED reads as
            // "you set this", everything else names its own provenance via [provenanceWords]
            // (ticket 18: a two-way test here would have silently rendered a LOOKUP row's prior
            // value, itself only a reviewed factory-lookup guess, as if the driver had typed it).
            "on file: ${intervalPhrase(row.currentMiles, row.currentMonths)} " +
                "(${provenanceWordsForSource(row.currentSource) ?: "you set this"})",
            style = LegionType.stamp,
            color = sem.faint,
        )
        Spacer(Modifier.height(4.dp))
        DeckButton(text = "ACCEPT CHANGE", onClick = onAccept)
    }
}

/**
 * POSSIBLE MATCH row (ticket 14 review, BLOCKING 1b): [row.factoryName][PopulatePossibleMatchRow.factoryName]
 * is what the factory lookup actually said, shown alongside the existing item the near-miss
 * comparator guessed it might be - both names visible, never one silently substituted for the
 * other. Two explicit buttons rather than one accept, since neither answer is a safe default:
 * [onSameThing] merges onto the existing row, [onAddAsNew] inserts [row.factoryName] verbatim as
 * its own new item.
 */
@Composable
private fun PossibleMatchRow(row: PopulatePossibleMatchRow, onSameThing: () -> Unit, onAddAsNew: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DeckRow(label = row.factoryName, value = intervalPhrase(row.proposedMiles, row.proposedMonths))
        Text(
            // Three-way via [provenanceWordsForSource] (ticket 18) - a two-way test here would have
            // silently rendered a LOOKUP-sourced existing row (an earlier populate's own accept) as
            // "you set this", the exact laundering ticket 18 exists to stop.
            "This looks like \"${row.existingName}\" already on file " +
                "(${intervalPhrase(row.currentMiles, row.currentMonths)}, " +
                "${provenanceWordsForSource(row.existingSource) ?: "you set this"}) - same thing?",
            style = LegionType.stamp,
            color = sem.faint,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(text = "SAME THING", onClick = onSameThing)
            DeckButton(text = "ADD AS NEW", onClick = onAddAsNew)
        }
    }
}

@Composable
private fun WouldRestoreRow(row: PopulateRestoreRow, onAccept: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DeckRow(label = row.serviceName, value = intervalPhrase(row.proposedMiles, row.proposedMonths))
        Text(
            // Softened alongside NotInScheduleRow (ticket 18): "the factory schedule still lists
            // it" was the same shape of over-claim off a single lookup run - this lookup is what
            // actually said so.
            "You deleted this item - this lookup still lists it.",
            style = LegionType.stamp,
            color = sem.estimated,
        )
        Spacer(Modifier.height(4.dp))
        DeckButton(text = "ADD IT BACK", onClick = onAccept)
    }
}

@Composable
private fun NotInScheduleRow(item: MaintenanceItem, onDelete: () -> Unit) {
    val sem = LocalLegionSemantics.current
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DeckRow(label = item.serviceName, value = intervalWords(item) ?: "no interval on file")
        Text(
            // Three-way (ticket 18) and deliberately SOFTENED: this used to assert "the factory
            // schedule doesn't list it", a claim about the car built off a single lookup run. Ticket
            // 18 showed that same lookup, run again minutes later on the same car, disagreed with
            // itself on three of eight items - so "this lookup didn't mention it" is what the app
            // actually knows, and "the factory schedule doesn't list it" is a claim it does not.
            // A single flaky sample is thin grounds for suggesting a driver delete a real service.
            when (item.intervalSource) {
                "SEEDED" -> "LEGION guessed this item, and this lookup didn't mention it either."
                "LOOKUP" -> "This came from an earlier factory lookup. This one didn't mention it."
                else -> "You added this yourself. This lookup didn't mention it, which doesn't mean it's wrong for this car."
            },
            style = LegionType.stamp,
            color = sem.faint,
        )
        Spacer(Modifier.height(4.dp))
        DeckButton(
            text = if (confirming) "TAP AGAIN TO DELETE" else "DELETE",
            destructive = true,
            confirming = confirming,
            onClick = {
                if (!confirming) confirming = true else onDelete()
            },
        )
    }
}

/** "every 7,500 mi or 6 mo" / "no interval given" for a raw proposed pair - [intervalWords] needs a real [com.kevin.legion.data.local.MaintenanceItem], this is the same phrase for the bare pair a [PopulateChangeRow]/[PopulateRestoreRow] carries. */
private fun intervalPhrase(miles: Int?, months: Int?): String {
    val parts = listOfNotNull(miles?.let { "${groupThousands(it)} mi" }, months?.let { "$it mo" })
    return if (parts.isEmpty()) "no interval given" else "every " + parts.joinToString(" or ")
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Populate: five categories", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewPopulateDiffContent() = LegionTheme {
    Surface {
        PopulateDiffContent(
            diff = PopulateDiff(
                wouldAdd = listOf(MaintenanceItem(vehicleId = "x", serviceName = "Coolant Flush", intervalMonths = 24)),
                wouldChange = listOf(
                    PopulateChangeRow(
                        serviceName = "Oil Change", currentMiles = 3000, currentMonths = null, currentSource = "SEEDED",
                        proposedMiles = 7500, proposedMonths = 6,
                    ),
                ),
                notInFactorySchedule = listOf(
                    MaintenanceItem(vehicleId = "x", serviceName = "Brake Fluid Flush", intervalMonths = 24, intervalSource = "SEEDED"),
                ),
                wouldRestore = listOf(PopulateRestoreRow(serviceName = "Tire Rotation", proposedMiles = 7500, proposedMonths = null)),
                possibleMatch = listOf(
                    PopulatePossibleMatchRow(
                        factoryName = "Wheel Alignment Check", existingName = "Alignment", existingSource = "SEEDED",
                        currentMiles = 15_000, currentMonths = null, proposedMiles = 15_000, proposedMonths = null,
                    ),
                ),
            ),
            onAccept = {}, onAddOutcome = { WriteOutcome(true, "ok") },
            onChangeOutcome = { WriteOutcome(true, "ok") },
            onDeleteOutcome = { WriteOutcome(true, "ok") },
            onRestoreOutcome = { WriteOutcome(true, "ok") },
            onMergeMatchOutcome = { WriteOutcome(true, "ok") },
            onAddAsNewOutcome = { WriteOutcome(true, "ok") },
        )
    }
}
