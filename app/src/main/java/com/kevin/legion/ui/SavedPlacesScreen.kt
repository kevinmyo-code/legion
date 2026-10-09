@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.location.GeocodedAddress
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.fleet.FleetSoftSurface
import com.kevin.legion.ui.navigation.LocalNavEntryPoints
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The dialog the Saved places screen is showing, if any. One state rather than five booleans, so
 * two dialogs can never be open at once and every step of a save (type, pick, confirm) is one
 * value.
 */
private sealed interface PlacesDialog {
    data object None : PlacesDialog

    /** Typing a label, plus an address when [byAddress]; otherwise the current location. */
    data class Save(val byAddress: Boolean) : PlacesDialog

    data class Pick(val label: String, val candidates: List<GeocodedAddress>) : PlacesDialog

    data class Replace(val label: String, val existing: TaggedPlace, val spot: PlaceController.Spot) : PlacesDialog

    data class Rename(val place: TaggedPlace) : PlacesDialog

    data class Forget(val place: TaggedPlace) : PlacesDialog
}

/**
 * `fleet/places` - the hands path for `tag_place`, `rename_place`, `forget_place` and
 * `get_current_location` (ADR 0035). **Not a second implementation of any of them**: every write
 * goes through [PlaceController.savePlace]/[PlaceController.savePlaceAt]/[PlaceController.renamePlace]/
 * [PlaceController.forgetPlace], the functions the voice tools call, and renders their sentence
 * unchanged.
 *
 * Saving by address (Kevin, 2026-10-09) asks the same two questions voice does - which match, and
 * whether to replace a place already under that name - with a picker and a dialog instead of a
 * spoken turn. Delete keeps its confirm step: "a misheard voice delete is why the confirm exists -
 * keep the same care by hand."
 *
 * Moved out of `FleetScreen.kt` (1,695 lines) when it grew these flows.
 */
/**
 * The screen's state and the five controller calls behind its buttons - a plain state holder, so
 * the composables below only render and forward clicks.
 */
private class SavedPlacesState(private val context: Context, private val scope: CoroutineScope) {
    var places by mutableStateOf(listOf<TaggedPlace>())
    var reloadNonce by mutableIntStateOf(0)
    var hasLocationPermission by mutableStateOf(LocationController.hasPermission(context))
    var currentLocationText by mutableStateOf("Checking...")
    var dialog by mutableStateOf<PlacesDialog>(PlacesDialog.None)
    var busy by mutableStateOf(false)

    /**
     * What the last action actually SAID - an ack, a refusal in the engine's own words, or the
     * reason nothing was saved. Persistent until the next action, never auto-cleared: on this
     * screen it is the only account of the write there is (CLAUDE.md section 7).
     */
    var lastActionMessage by mutableStateOf<String?>(null)

    suspend fun reload() {
        places = PlaceController.all(context)
        LocationController.init(context)
        hasLocationPermission = LocationController.hasPermission(context)
        currentLocationText = currentLocationReadout(context)
    }

    fun save(label: String, address: String?) =
        act { next(PlaceController.savePlace(context, label, address, confirmed = false)) }

    fun pick(label: String, c: GeocodedAddress) {
        val spot = PlaceController.Spot(c.latitude, c.longitude, c.address)
        act { next(PlaceController.savePlaceAt(context, label, spot, confirmed = false)) }
    }

    fun replace(label: String, spot: PlaceController.Spot) =
        act { next(PlaceController.savePlaceAt(context, label, spot, confirmed = true)) }

    fun rename(from: String, to: String) = act { said(PlaceController.renamePlace(context, from, to).message) }

    fun forget(label: String) =
        act { said(PlaceController.forgetPlace(context, label, confirmed = true).message) }

    /** Runs one controller call off the click, then shows the next dialog (or none) and reloads. */
    private fun act(block: suspend () -> PlacesDialog) {
        busy = true
        scope.launch {
            dialog = block()
            busy = false
            reloadNonce++
        }
    }

    private fun said(message: String): PlacesDialog {
        lastActionMessage = message
        return PlacesDialog.None
    }

    /** A save's outcome is either a sentence, or the next question for the user. */
    private fun next(outcome: PlaceController.SaveOutcome): PlacesDialog = when (outcome) {
        is PlaceController.SaveOutcome.Choose -> PlacesDialog.Pick(outcome.label, outcome.candidates)
        is PlaceController.SaveOutcome.NeedsConfirm ->
            PlacesDialog.Replace(outcome.label, outcome.existing, outcome.spot)
        else -> said(outcome.message)
    }
}

/**
 * `fleet/places` - the hands path for `tag_place`, `rename_place`, `forget_place` and
 * `get_current_location` (ADR 0035). **Not a second implementation of any of them**: every write
 * goes through [PlaceController.savePlace]/[PlaceController.savePlaceAt]/[PlaceController.renamePlace]/
 * [PlaceController.forgetPlace], the functions the voice tools call, and renders their sentence
 * unchanged.
 *
 * Saving by address (Kevin, 2026-10-09) asks the same two questions voice does - which match, and
 * whether to replace a place already under that name - with a picker and a dialog instead of a
 * spoken turn. Delete keeps its confirm step: "a misheard voice delete is why the confirm exists -
 * keep the same care by hand."
 *
 * Moved out of `FleetScreen.kt` (1,695 lines) when it grew these flows.
 */
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SavedPlacesState(context, scope) }
    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> state.reloadNonce++ }

    LaunchedEffect(state.reloadNonce) { state.reload() }

    FleetSoftSurface {
        SavedPlacesContent(
            currentLocationText = state.currentLocationText,
            hasLocationPermission = state.hasLocationPermission,
            busy = state.busy,
            lastActionMessage = state.lastActionMessage,
            places = state.places,
            onBack = onBack,
            onGrant = {
                requestLocation.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onDialog = { state.dialog = it },
        )
    }
    SavedPlacesDialogs(state)
}

@Composable
private fun SavedPlacesContent(
    currentLocationText: String,
    hasLocationPermission: Boolean,
    busy: Boolean,
    lastActionMessage: String?,
    places: List<TaggedPlace>,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onDialog: (PlacesDialog) -> Unit,
) {
    val navEntry = LocalNavEntryPoints.current
    val sem = LocalLegionSemantics.current
    Column(modifier = Modifier.padding(16.dp)) {
        DeckScreenHeader(title = "Saved places", onBack = onBack, accent = AreaAccent.FLEET)
        Text(
            currentLocationText,
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (!hasLocationPermission) LocationPermissionRow(onGrant)

        DeckButton(
            text = "Save by address",
            onClick = { onDialog(PlacesDialog.Save(byAddress = true)) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        DeckButton(
            text = "Tag current location",
            onClick = { onDialog(PlacesDialog.Save(byAddress = false)) },
            enabled = hasLocationPermission && !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        lastActionMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            DeckPane(header = "Last action") {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (places.isEmpty()) {
            Text("No saved places yet", style = MaterialTheme.typography.bodyMedium, color = sem.faint)
        } else {
            LazyColumn {
                items(places, key = { it.label }) { place ->
                    SavedPlaceRow(
                        place = place,
                        // mapbox-nav ticket 10: opens the native nav screen on this place.
                        onNavigate = { navEntry.openFor(place.label) },
                        onRename = { onDialog(PlacesDialog.Rename(place)) },
                        onDelete = { onDialog(PlacesDialog.Forget(place)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Location permission absent is an honest state naming the grant, not a blank screen. Saving by
 * address does not need it, so only the current-location half says it is blocked. */
@Composable
private fun LocationPermissionRow(onGrant: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LEGION doesn't have location permission, so tagging where you are and the " +
                    "current-location line above can't work until it's granted. Saving by address " +
                    "still works.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** One saved place: label, its address (or that none is on file), and its three actions. */
@Composable
private fun SavedPlaceRow(
    place: TaggedPlace,
    onNavigate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                place.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(addressLine(place), style = LegionType.stamp, color = sem.faint)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNavigate) { Text("Navigate") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** The address under a label. A place with none says so in words, with the coordinates that ARE
 * the place, rather than a blank line that reads like a missing render. */
internal fun addressLine(place: TaggedPlace): String =
    place.address ?: "No address on file (lat ${place.latitude}, lng ${place.longitude})"

@Composable
private fun SavedPlacesDialogs(state: SavedPlacesState) {
    val dismiss = { state.dialog = PlacesDialog.None }
    when (val dialog = state.dialog) {
        PlacesDialog.None -> Unit
        is PlacesDialog.Save -> SavePlaceDialog(dialog.byAddress, state.busy, dismiss, state::save)
        is PlacesDialog.Pick -> PickAddressDialog(dialog.candidates, dismiss) { state.pick(dialog.label, it) }
        is PlacesDialog.Replace -> ConfirmDialog(
            title = "Replace \"${dialog.label}\"?",
            body = "\"${dialog.label}\" is already saved, ${addressLine(dialog.existing)}. Replace it with " +
                "${dialog.spot.address ?: "where you are now"}? The old spot will be lost.",
            confirm = "Replace",
            onDismiss = dismiss,
        ) { state.replace(dialog.label, dialog.spot) }
        is PlacesDialog.Rename ->
            RenamePlaceDialog(dialog.place, state.busy, dismiss) { state.rename(dialog.place.label, it) }
        is PlacesDialog.Forget -> ConfirmDialog(
            title = "Delete \"${dialog.place.label}\"?",
            body = "This removes the saved place, ${addressLine(dialog.place)}. It can be saved again " +
                "later, but this exact entry is gone.",
            confirm = "Delete",
            onDismiss = dismiss,
        ) { state.forget(dialog.place.label) }
    }
}

/** Label, plus an address in by-address mode. Same normalization by hand as by voice: the
 * controller folds a typed "Home" and a spoken "this is my home" onto the same row. */
@Composable
private fun SavePlaceDialog(
    byAddress: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, address: String?) -> Unit,
) {
    SoftTheme {
        var label by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        val ready = label.isNotBlank() && (!byAddress || address.isNotBlank()) && !busy
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (byAddress) "Save by address" else "Tag current location") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name, e.g. home or Katie House") },
                        singleLine = true,
                    )
                    if (byAddress) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Street address, e.g. 123 Main St, Katy, TX") },
                        )
                    }
                    if (busy) Text("Looking it up...", style = LegionType.stamp)
                }
            },
            confirmButton = {
                TextButton(enabled = ready, onClick = { onSave(label, address.takeIf { byAddress }) }) {
                    Text(if (byAddress) "Look up and save" else "Tag")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** The address matched several places: the user picks one, and only that one is saved. */
@Composable
private fun PickAddressDialog(
    candidates: List<GeocodedAddress>,
    onDismiss: () -> Unit,
    onPick: (GeocodedAddress) -> Unit,
) {
    SoftTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Which address?") },
            text = {
                Column {
                    Text("That matched more than one place. Nothing is saved until you pick one.")
                    candidates.forEach { c ->
                        Text(
                            c.address,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenamePlaceDialog(
    place: TaggedPlace,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    SoftTheme {
        var to by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Rename \"${place.label}\"") },
            text = {
                Column {
                    Text("Same spot, ${addressLine(place)}. Only the name changes.", style = LegionType.stamp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text("New name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = to.isNotBlank() && !busy, onClick = { onRename(to) }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** A real pause before a destructive write - the one piece of care a hands path can offer that
 * voice cannot. Used for delete and for replacing a place under a name already in use. */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SoftTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/**
 * The current-location line at the top of [SavedPlacesScreen]. Calls
 * [LiveToolbox.resolveCurrentLocation] directly, the same function `get_current_location` resolves
 * against, and only re-words the four outcomes. One decision, two renderers.
 */
private suspend fun currentLocationReadout(context: Context): String =
    when (val readout = LiveToolbox.resolveCurrentLocation(context)) {
        LiveToolbox.LocationReadout.NoPermission -> "Current location: unknown (no location permission)."
        LiveToolbox.LocationReadout.ProvidersOff -> "Current location: unknown (location services are off)."
        LiveToolbox.LocationReadout.NoFix -> "Current location: unknown (no GPS fix yet)."
        is LiveToolbox.LocationReadout.Available ->
            if (readout.label != null) {
                "Current location: ${readout.label} ${readout.coords}"
            } else {
                "Current location: ${readout.coords} (couldn't resolve an address)"
            }
    }
