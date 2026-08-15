package com.kevin.legion.ui.fleet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome
import kotlinx.coroutines.launch

/**
 * The CARS roster's pure logic and rows, for `ui/CarsScreen.kt`. Same split as
 * [FleetRows.kt]: everything here is either a pure function ([buildCarRows],
 * unit-tested in `CarRowsTest` with no Room or Android dependency) or a
 * display-only Composable.
 *
 * **Why this screen exists at all.** [com.kevin.legion.vehicle.ActiveVehicle]'s
 * doc comment has referred to "[CarsScreen]'s explicit picker" since the
 * Midnight AI port, but that screen never came across - nothing in `ui/` ever
 * wrote [com.kevin.legion.vehicle.ActiveVehicle.select], so the active car
 * could only ever be the connected dongle's MAC or the `default` placeholder.
 * The 2026-08-04 Midnight AI import made that visible and expensive: three
 * vehicles and 11,532 telemetry rows landed in the database, the Outlander was
 * re-keyed off the `default` sentinel onto its own synthetic id
 * (`imported-mitsubishi-outlander-2020`), and FLEET went on rendering the blank
 * placeholder because no code path could point the resolver at anything else.
 */

// --------------------------------------------------------------- pure

/** Telemetry facts about one car, read from `obd_samples` by the state holder. */
data class CarTelemetry(val sampleCount: Int, val lastSampleMs: Long?)

/** One roster row, already resolved to display strings. */
data class CarRowView(
    val vehicleId: String,
    val label: String,
    val sub: String,
    /** True if [com.kevin.legion.vehicle.ActiveVehicle.current] resolves to this car right now. */
    val active: Boolean,
    /** True if the driver PICKED this car, as opposed to it being the adapter's own. */
    val explicit: Boolean,
    val archived: Boolean,
)

/**
 * Builds the roster.
 *
 * **A car is never labelled with something it did not state.** A placeholder row
 * (see [VehicleController]'s `seedVehicle`) has no make, model or year, so
 * [VehicleController.displayLabel] returns blank and the fallback is the row's
 * own `name` - "this car" - and then, only if even that is empty, the raw id.
 * The id is a last resort rather than a first choice because a MAC or a
 * `car:`-prefixed UUID tells the driver nothing, but it does at least
 * distinguish two otherwise identical blank rows, which is exactly the state a
 * device that ran both codebases can end up in.
 *
 * **Ordering:** the active car first (it is what FLEET is showing, so it
 * belongs at the top), then the rest in label order, then archived cars last.
 * Archived rows are included only when [showArchived] - they are hidden from
 * the roster by [VehicleController.archive]'s contract, and the toggle is what
 * makes the operation reversible rather than a one-way door.
 */
fun buildCarRows(
    vehicles: List<Vehicle>,
    telemetry: Map<String, CarTelemetry>,
    selectedId: String?,
    resolvedId: String,
    showArchived: Boolean,
): List<CarRowView> =
    vehicles
        .filter { showArchived || !it.archived }
        .map { vehicle ->
            CarRowView(
                vehicleId = vehicle.obdMac,
                label = carLabel(vehicle),
                // Spec first when the label is a nickname, so "the truck" still says what it is.
                sub = listOf(carSpecPrefix(vehicle), telemetrySub(telemetry[vehicle.obdMac]))
                    .filter { it.isNotBlank() }
                    .joinToString("  ·  "),
                active = vehicle.obdMac == resolvedId,
                explicit = vehicle.obdMac == selectedId,
                archived = vehicle.archived,
            )
        }
        .sortedWith(compareBy({ it.archived }, { !it.active }, { it.label.lowercase() }))

/**
 * The driver's own name for the car if they gave it one ("the truck"), else
 * "2020 Mitsubishi Outlander", else the raw id. See [buildCarRows].
 *
 * **The name wins now, and it did not before (Kevin, 2026-08-13).** This used to
 * prefer [VehicleController.displayLabel], falling back to `name` only when the
 * car had no make or model - which meant renaming a car that HAD a make and model
 * changed nothing the driver could see. That was invisible while renaming was
 * voice-only and became a bug the moment a RENAME button existed: a control that
 * appears to do nothing. A nickname is something the driver stated, so preferring
 * it does not violate [buildCarRows]'s "never labelled with something it did not
 * state" rule - and the spec is not lost, it moves to the row's sub-line.
 */
internal fun carLabel(vehicle: Vehicle): String {
    val spec = VehicleController.displayLabel(vehicle)
    val name = vehicle.name.trim()
    return when {
        name.isBlank() -> spec.ifBlank { vehicle.obdMac }
        spec.isBlank() -> name
        // `name` is not always a nickname the driver chose - import sets it from the model, so the
        // Outlander arrives already called "Outlander". Preferring it blindly would DOWNGRADE
        // "2020 Mitsubishi Outlander" to "Outlander" and lose the year and make. A name the spec
        // already contains adds nothing, so the spec keeps the row; a name the spec does not
        // contain is the driver having said something of their own, and it wins.
        spec.contains(name, ignoreCase = true) -> spec
        else -> name
    }
}

/**
 * The spec line that sits under a renamed car, so calling it "the truck" never
 * costs the driver the knowledge that it is a 2020 Mitsubishi Outlander. Blank
 * when the car has no spec, or when [carLabel] is already showing it.
 */
internal fun carSpecPrefix(vehicle: Vehicle): String {
    val spec = VehicleController.displayLabel(vehicle)
    return if (spec.isBlank() || spec == carLabel(vehicle)) "" else spec
}

/**
 * "5,242 readings - last Jun 12, 2026", or a plain statement that the car has
 * none. A car with zero telemetry is the normal state for a freshly created
 * profile, so it reads as a fact rather than as a warning.
 */
internal fun telemetrySub(telemetry: CarTelemetry?): String {
    val count = telemetry?.sampleCount ?: 0
    if (count == 0) return "no telemetry recorded"
    val last = telemetry?.lastSampleMs
    val readings = "${groupThousands(count)} readings"
    return if (last == null) readings else "$readings - last ${shortDate(last)}"
}

// --------------------------------------------------------------- rows

/**
 * One car in the roster. **"On this phone" is spelled out** rather than shown
 * as a bare marker, for the same reason
 * [com.kevin.legion.ui.companions.CompanionRow] spells it out: the `vehicles`
 * table syncs across every device on the shared Google account, but the active
 * selection deliberately does NOT
 * ([com.kevin.legion.vehicle.ActiveVehicle]'s doc), so this phone and the other
 * phone can legitimately disagree about which car they are in.
 *
 * The two active states are distinguished on purpose. "FOLLOWING THE ADAPTER"
 * means nobody chose this car - it is whatever dongle happens to be connected,
 * and it will change by itself when the dongle does. "ACTIVE ON THIS PHONE"
 * means the driver picked it and it will stay picked.
 *
 * Tapping an already-active-and-explicit row does nothing; there is nothing to
 * switch to. Tapping an active-but-auto row DOES fire, because pinning the
 * adapter's current car is a real change of state.
 */
@Composable
fun CarRow(
    row: CarRowView,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !(row.active && row.explicit) && !row.archived, onClick = onActivate)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.archived) sem.ghost else MaterialTheme.colorScheme.onSurface,
            )
            Text(row.sub, style = LegionType.stamp, color = if (row.archived) sem.ghost else sem.faint)
            if (row.active) {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    if (row.explicit) "ACTIVE ON THIS PHONE" else "ACTIVE - FOLLOWING THE ADAPTER",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // RENAME is offered regardless of archived state - unlike ARCHIVE/RESTORE, which are a
        // pair of opposite actions on the same field, renaming an archived car is still a sane
        // thing to want (fixing a typo before deciding whether to bring it back).
        TextButton(onClick = onRename) {
            Text("RENAME", style = LegionType.stamp, color = if (row.archived) sem.ghost else MaterialTheme.colorScheme.primary)
        }
        if (row.archived) {
            TextButton(onClick = onUnarchive) {
                Text("RESTORE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            TextButton(onClick = onArchive) {
                Text("ARCHIVE", style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

/**
 * The "follow the adapter" row that sits above the cars themselves - the
 * null selection [com.kevin.legion.vehicle.ActiveVehicle.select] takes, which
 * is the behaviour of an install that never opens this screen.
 *
 * [resolvesTo] names the car auto currently lands on, so choosing it is not a
 * blind choice. It is null when nothing is connected and no row exists yet.
 */
@Composable
fun AutoCarRow(isAuto: Boolean, resolvesTo: String?, onSelect: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isAuto, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Follow the adapter", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (resolvesTo != null) "whichever car the OBD dongle is plugged into - now: $resolvesTo"
                else "whichever car the OBD dongle is plugged into",
                style = LegionType.stamp,
                color = sem.faint,
            )
            if (isAuto) {
                Spacer(Modifier.padding(top = 2.dp))
                Text("ACTIVE ON THIS PHONE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// --------------------------------------------------------------- manual add / rename

/**
 * The manual "ADD CAR" form (Kevin, 2026-08-13: "i need to be able to manually rename / add cars
 * too" - fleet was voice-only for both). Fields and their requiredness mirror
 * [com.kevin.legion.vehicle.VehicleController.addVehicle] exactly (make and model required, year
 * optional, trim and a nickname optional) - this dialog adds no field the data layer doesn't have
 * and validates nothing [CarManageResolver.validateAddCar] doesn't already decide.
 *
 * [existingLabels] is every OTHER car's [carLabel] already in the roster (archived included - a
 * duplicate name is confusing whether or not the other car is currently hidden), used only for the
 * live "you already have one of these" nickname check; [onAdd] is a direct pass-through to
 * [com.kevin.legion.vehicle.VehicleController.addVehicle] via the caller, which also owns that
 * function's OWN make/model/year duplicate check - this dialog never second-guesses that result,
 * it only decides when CONFIRM may be pressed at all.
 */
@Composable
fun AddCarDialog(
    existingLabels: List<String>,
    onDismiss: () -> Unit,
    onAdd: (year: Int, make: String, model: String, trim: String, name: String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var yearText by remember { mutableStateOf("") }
    var trim by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    val validation = CarManageResolver.validateAddCar(make, model, yearText, name, existingLabels)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a car") },
        text = {
            Column {
                OutlinedTextField(value = make, onValueChange = { make = it }, singleLine = true, label = { Text("Make") })
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(value = model, onValueChange = { model = it }, singleLine = true, label = { Text("Model") })
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(value = yearText, onValueChange = { yearText = it }, singleLine = true, label = { Text("Year (optional)") })
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(value = trim, onValueChange = { trim = it }, singleLine = true, label = { Text("Trim (optional)") })
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nickname (optional)") })
                if (validation.error != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
                    Text(validation.error, style = LegionType.stamp, color = sem.estimated)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(CarManageResolver.parseYear(yearText) ?: 0, make.trim(), model.trim(), trim.trim(), name.trim()) },
                enabled = validation.isValid,
            ) { Text("ADD CAR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/**
 * The manual RENAME form. Pre-fills from [currentLabel] (the row's own [carLabel], what the driver
 * actually sees in the roster) rather than the raw stored `name` field, which a confirmed car's
 * displayed label overrides ([carLabel]'s own doc) - starting from a blank or stale field would
 * make "rename" look broken the moment a car has a make and model on file.
 *
 * Writes ONLY the `name` field, via [com.kevin.legion.vehicle.VehicleController.correctVehicle] -
 * the exact shape the `manage_vehicle` voice tool's `"rename"` action already uses
 * (`LiveToolbox.manageVehicle`), never the wider "correct the badge" form
 * [com.kevin.legion.vehicle.VehicleController.correctVehicle]'s `make`/`model`/`year` params cover.
 * [otherLabels] must already exclude the car being renamed - see [CarManageResolver.validateRename].
 */
@Composable
fun RenameCarDialog(
    currentLabel: String,
    otherLabels: List<String>,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var name by remember(currentLabel) { mutableStateOf(currentLabel) }

    val validation = CarManageResolver.validateRename(name, otherLabels)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                if (validation.error != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
                    Text(validation.error, style = LegionType.stamp, color = sem.estimated)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRename(name.trim()) }, enabled = validation.isValid) { Text("RENAME") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

// ------------------------------------------------------------------------ manual odometer entry

/**
 * The odometer's ONE manual-entry control (ticket 10,
 * `.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`: "one control, reused"). FLEET's
 * CARS pane (`ui/FleetScreen.kt`) is this dialog's only caller today; ticket 09's triage screen and
 * ticket 14's future registration form are meant to reuse THIS composable rather than
 * re-implementing the field/button/validation shape - three copies would be three places to get the
 * estimate label wrong, the ticket's own words.
 *
 * [currentValueText]/[currentCaveatText] are [VehicleController.mileageValueText]/
 * [VehicleController.mileageCaveat]'s own strings - shown so the driver can see what they are about
 * to overwrite before typing a new number, same "current" context every other edit dialog on this
 * screen ([RenameCarDialog]'s `currentLabel`) already shows.
 *
 * [onSubmit] is a direct pass-through to [com.kevin.legion.vehicle.VehicleController.setOdometer];
 * this dialog never second-guesses its [WriteOutcome]. Ticket 10 §7's rule - a reading below the
 * current estimate is QUESTIONED, never refused - lives entirely in [VehicleController.setOdometer]
 * (via [VehicleController.odometerQuestionNote]), so SET always submits whatever parses as a valid
 * integer and simply shows back whatever the write itself said. The dialog stays open after a
 * successful submit (rather than auto-dismissing) so the driver has time to actually read that
 * reply - the questioned case exists precisely because it says something worth reading.
 */
@Composable
fun SetOdometerDialog(
    currentValueText: String,
    currentCaveatText: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (Int) -> WriteOutcome,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    var milesText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val miles = milesText.trim().toIntOrNull()

    DeckDialog(title = "Set Odometer", onDismissRequest = onDismiss) {
        Text(
            if (currentValueText.isBlank()) "No reading on file yet." else "Current: $currentValueText",
            style = LegionType.stamp,
            color = sem.faint,
        )
        if (currentCaveatText.isNotBlank()) {
            Text(currentCaveatText, style = LegionType.stamp, color = sem.estimated)
        }
        Spacer(Modifier.height(8.dp))
        DeckTextField(
            value = milesText,
            onValueChange = { milesText = it; statusText = null },
            label = "Miles",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (statusText != null) {
            Spacer(Modifier.height(8.dp))
            Text(statusText!!, style = LegionType.stamp, color = sem.faint)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(text = "CANCEL", onClick = onDismiss)
            DeckButton(
                text = "SET",
                enabled = miles != null && !submitting,
                onClick = {
                    val m = miles ?: return@DeckButton
                    submitting = true
                    scope.launch {
                        val outcome = onSubmit(m)
                        submitting = false
                        statusText = outcome.message
                        if (outcome.success) milesText = ""
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Car row: active, picked by the driver", widthDp = 360)
@Composable
private fun PreviewCarRowActiveExplicit() = LegionTheme {
    Surface {
        CarRow(
            CarRowView(
                vehicleId = "imported-mitsubishi-outlander-2020",
                label = "2020 Mitsubishi Outlander",
                sub = "5,242 readings - last Jun 12, 2026",
                active = true, explicit = true, archived = false,
            ),
            onActivate = {}, onRename = {}, onArchive = {}, onUnarchive = {},
        )
    }
}

@Preview(name = "Car row: active because the dongle says so", widthDp = 360)
@Composable
private fun PreviewCarRowActiveAuto() = LegionTheme {
    Surface {
        CarRow(
            CarRowView(
                vehicleId = "AA:BB:CC:DD:EE:FF",
                label = "this car",
                sub = "no telemetry recorded",
                active = true, explicit = false, archived = false,
            ),
            onActivate = {}, onRename = {}, onArchive = {}, onUnarchive = {},
        )
    }
}

@Preview(name = "Car row: archived", widthDp = 360)
@Composable
private fun PreviewCarRowArchived() = LegionTheme {
    Surface {
        CarRow(
            CarRowView(
                vehicleId = "car:0d1f",
                label = "2014 Mazda 3",
                sub = "812 readings - last Jan 4, 2025",
                active = false, explicit = false, archived = true,
            ),
            onActivate = {}, onRename = {}, onArchive = {}, onUnarchive = {},
        )
    }
}

@Preview(name = "Auto row: selected", widthDp = 360)
@Composable
private fun PreviewAutoRow() = LegionTheme {
    Surface { AutoCarRow(isAuto = true, resolvesTo = "this car", onSelect = {}) }
}

@Preview(name = "Add a car: blank form", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewAddCarDialog() = LegionTheme {
    AddCarDialog(existingLabels = listOf("2020 Mitsubishi Outlander"), onDismiss = {}, onAdd = { _, _, _, _, _ -> })
}

@Preview(name = "Rename: prefilled from the current label", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewRenameCarDialog() = LegionTheme {
    RenameCarDialog(
        currentLabel = "2020 Mitsubishi Outlander",
        otherLabels = listOf("2014 Mazda 3"),
        onDismiss = {}, onRename = {},
    )
}

@Preview(name = "Set odometer: estimate between readings", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewSetOdometerDialogEstimate() = LegionTheme {
    SetOdometerDialog(
        currentValueText = "about 227,900 mi",
        currentCaveatText = "estimated, last confirmed 3 days ago",
        onDismiss = {},
        onSubmit = { WriteOutcome(true, "Got it, filed away.") },
    )
}

@Preview(name = "Set odometer: no reading on file yet", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewSetOdometerDialogUnset() = LegionTheme {
    SetOdometerDialog(
        currentValueText = "",
        currentCaveatText = "",
        onDismiss = {},
        onSubmit = { WriteOutcome(true, "Got it, filed away.") },
    )
}
