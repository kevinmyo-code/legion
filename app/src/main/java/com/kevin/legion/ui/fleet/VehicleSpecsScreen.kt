package com.kevin.legion.ui.fleet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.IdentityWriteResult
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleSpecController
import com.kevin.legion.vehicle.VinRefreshResult
import kotlinx.coroutines.launch

/**
 * SPECS - FLEET's fifth in-screen drilldown: the car's VIN and its decoded
 * factory specs.
 *
 * **Why it exists.** `VehicleSpec` has stored a `vin` column since the port and
 * `lookup_vin` has been writing to it, but NOTHING in `ui/` ever read the table
 * - a grep for `VehicleSpecController` across `ui/` returned nothing at all. The
 * VIN was readable only by asking out loud, which is no use when you want to
 * copy it into an insurance form. Fourth instance of this branch's recurring
 * shape: data captured, never surfaced.
 *
 * **The VIN is presented as copyable, not just legible.** The realistic reason
 * to look up your own VIN is to paste it somewhere, and a 17-character
 * alphanumeric string is exactly the kind of thing that gets mis-transcribed.
 *
 * **Decoded specs are stamped with when they were decoded.** They come from
 * NHTSA vPIC at lookup time, not live off the car, and an undated spec sheet
 * reads as current forever.
 */
@Composable
fun VehicleSpecsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()

    var spec by remember { mutableStateOf<VehicleSpec?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // Ticket 04's stored-VIN reconcile - separate state from the RE-READ/READ VIN flow above:
    // this one needs no adapter, only network, so it must be driveable while `reading` is false
    // and independent of `connected`.
    var reconciling by remember { mutableStateOf(false) }
    var reconcileMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        spec = VehicleSpecController.current(context)
        loaded = true
    }

    VehicleSpecsContent(
        spec = spec,
        loaded = loaded,
        connected = connectionState == ObdBluetoothManager.ConnectionState.CONNECTED,
        reading = reading,
        failure = failure,
        reconciling = reconciling,
        reconcileMessage = reconcileMessage,
        onBack = onBack,
        onCopyVin = { vin -> copyToClipboard(context, vin) },
        onReadVin = {
            reading = true
            failure = null
            reconcileMessage = null
            scope.launch {
                val result = runCatching { VehicleSpecController.refreshFromObd(context) }
                    .getOrElse { VinRefreshResult.DecodeFailed }
                reading = false
                if (result is VinRefreshResult.Decoded) {
                    reloadKey++
                    // READ VIN performs the identity write-back too, as a side effect of the same
                    // decode - so it must REPORT it, exactly as SYNC ID FROM VIN does. Without
                    // this the two callers of the same write-back disagreed: one said every
                    // outcome in words, the other said none of them, and a refused write (the
                    // conflict case - "this decode may not describe this car") was invisible.
                    // A write nobody is told about is the defect class this whole map is closing;
                    // it does not stop being one because the button had another job as well.
                    // Caught on review, 2026-08-15, before this reached the device.
                    reconcileMessage = reconcileOutcomeText(result)
                } else {
                    // Three different failures land here and the driver cannot
                    // act on "it didn't work": say which one it was.
                    failure = when {
                        !ObdBluetoothManager.isConnected ->
                            "The adapter dropped before the read finished. Reconnect and try again."
                        else ->
                            "No usable VIN came back. Cars built before roughly 2001 often do not " +
                                "report one over OBD-II at all, and the decode also needs a network " +
                                "connection. Neither is a fault with the car."
                    }
                }
            }
        },
        onReconcileIdentity = {
            reconciling = true
            reconcileMessage = null
            scope.launch {
                val result = runCatching { VehicleSpecController.reconcileIdentityFromStoredVin(context) }
                    .getOrElse { VinRefreshResult.DecodeFailed }
                reconciling = false
                reconcileMessage = reconcileOutcomeText(result)
                // Reconciling re-decodes and re-saves the spec row too (decodedAt moves), so the
                // pane above should pick that up the same way a fresh READ VIN does.
                if (result is VinRefreshResult.Decoded) reloadKey++
            }
        },
    )
}

private fun copyToClipboard(context: Context, vin: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("VIN", vin))
}

/** Plain UI: state in, callbacks out, no controller or DAO reference. */
@Composable
fun VehicleSpecsContent(
    spec: VehicleSpec?,
    loaded: Boolean,
    connected: Boolean,
    reading: Boolean,
    failure: String?,
    reconciling: Boolean,
    reconcileMessage: String?,
    onBack: () -> Unit,
    onCopyVin: (String) -> Unit,
    onReadVin: () -> Unit,
    onReconcileIdentity: () -> Unit,
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
                "SPECS",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "vin-pane") {
                    DeckPane(header = "VIN") {
                        val vin = spec?.vin.orEmpty()
                        if (vin.isNotBlank()) {
                            Text(
                                vin,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SpecAction("COPY", onClick = { onCopyVin(vin) })
                                if (!reading) SpecAction("RE-READ", onClick = onReadVin)
                            }
                        } else if (loaded) {
                            Text(
                                if (connected) {
                                    "No VIN on file. Read it off the adapter - it takes a few seconds."
                                } else {
                                    "No VIN on file, and no adapter connected. The VIN is read off " +
                                        "the OBD port, so plug the dongle in first."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.faint,
                                modifier = Modifier.padding(12.dp),
                            )
                            // Offered even when disconnected: the state above is a
                            // snapshot, and a button that vanishes is worse than one
                            // that explains itself when pressed.
                            if (!reading) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    SpecAction("READ VIN", onClick = onReadVin)
                                }
                            }
                        }
                        if (reading) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Reading the VIN and decoding it with NHTSA...",
                                    style = LegionType.stamp,
                                    color = sem.faint,
                                )
                            }
                        }
                        if (failure != null) {
                            Text(
                                failure,
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.faint,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        // Ticket 04's stored-VIN reconcile: re-decodes whatever VIN is ALREADY on
                        // file above and writes its identity onto the car - repairs a
                        // `vehicle_specs` row that decoded weeks ago but never reached `vehicles`
                        // (Kevin's Jeep, since 2026-07-26). Deliberately offered regardless of
                        // `connected` or whether `vin` above is blank in this render pass - it
                        // reads its own VIN fresh from storage when pressed, and needs network, not
                        // a live dongle.
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!reconciling) {
                                SpecAction(
                                    "SYNC ID FROM VIN",
                                    onClick = onReconcileIdentity,
                                    announce = reconcileMessage,
                                )
                            }
                        }
                        if (reconciling) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Re-decoding the VIN on file and reconciling the car's identity...",
                                    style = LegionType.stamp,
                                    color = sem.faint,
                                )
                            }
                        }
                        // Every outcome is said in words here - applied (and which fields),
                        // nothing to change, a conflict (and which fields, both values), no VIN on
                        // file, decode failed/offline. Never a bare spinner that ends in silence,
                        // never colour- or glyph-only (CLAUDE.md §7).
                        if (reconcileMessage != null) {
                            Text(
                                reconcileMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.faint,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                item(key = "specs-pane") {
                    Spacer(Modifier.height(12.dp))
                    val rows = specRows(spec)
                    DeckPane(header = "Factory") {
                        if (rows.isEmpty()) {
                            Text(
                                if (spec?.vin.isNullOrBlank()) {
                                    "Nothing decoded yet. Read the VIN above and these fill in."
                                } else {
                                    "The VIN decoded, but NHTSA returned no factory detail for it. " +
                                        "That is common on imports and older vehicles."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.faint,
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            rows.forEach { (label, value) -> DeckRow(label = label, value = value) }
                            if (spec != null && spec.decodedAt > 0L) {
                                // Decoded, not live. An undated spec sheet reads
                                // as current forever.
                                Text(
                                    "Decoded ${shortDate(spec.decodedAt)} from NHTSA vPIC. Not a live reading.",
                                    style = LegionType.stamp,
                                    color = sem.ghost,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The decoded fields worth showing, blank ones dropped.
 *
 * Internal so the "an absent field is omitted, never rendered as an empty or
 * zero value" rule is testable without a database.
 */
internal fun specRows(spec: VehicleSpec?): List<Pair<String, String>> {
    if (spec == null) return emptyList()
    fun text(value: String) = value.trim().takeIf { it.isNotBlank() }
    return buildList {
        text(spec.manufacturer)?.let { add("Manufacturer" to it) }
        text(spec.series)?.let { add("Series" to it) }
        text(spec.bodyClass)?.let { add("Body" to it) }
        spec.doors?.let { add("Doors" to it.toString()) }
        spec.engineCylinders?.let { add("Cylinders" to it.toString()) }
        spec.displacementL?.let { add("Displacement" to "%.1fL".format(it)) }
        spec.engineHp?.let { add("Horsepower" to "$it hp") }
        text(spec.engineConfig)?.let { add("Engine" to it) }
        text(spec.fuelType)?.let { add("Fuel" to it) }
        val transmission = listOf(spec.transmissionSpeeds, spec.transmissionStyle)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        text(transmission)?.let { add("Transmission" to it) }
        text(spec.driveType)?.let { add("Drivetrain" to it) }
        val plant = listOf(spec.plantCity, spec.plantCountry)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
        text(plant)?.let { add("Assembled in" to it) }
        text(spec.paintColor)?.let { add("Paint" to it) }
        text(spec.paintCode)?.let { add("Paint code" to it) }
    }
}

/**
 * Bordered stamp button, local to this screen - same call as ObdDeviceScreen's DeckAction.
 *
 * [announce] carries a [stateDescription] on top of the button's own click semantics
 * (`ui/common/DeckControls.kt`'s [androidx.compose.ui.semantics.stateDescription] convention,
 * used by ticket 04's SYNC ID action to speak its outcome - applied/conflict/nothing-to-change/
 * etc - to TalkBack the same as it's shown on screen, never a colour- or glyph-only signal).
 */
@Composable
private fun SpecAction(text: String, onClick: () -> Unit, announce: String? = null) {
    Box(
        Modifier
            .sizeIn(minHeight = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .let { if (announce != null) it.semantics { stateDescription = announce } else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Renders a [VinRefreshResult] into the plain-language line ticket 04 §5 asks for - every
 * outcome said in words, on the surface itself (not TalkBack-only), never a bare spinner that
 * ends in silence and never a colour- or glyph-only signal.
 *
 * Internal so the outcome text is testable without Compose - CLAUDE.md §11's testing convention,
 * matching [specRows]'s own reason for being `internal`.
 */
internal fun reconcileOutcomeText(result: VinRefreshResult): String = when (result) {
    VinRefreshResult.NoStoredVin ->
        "No VIN on file yet to reconcile. Read one off the adapter above first."
    VinRefreshResult.DecodeFailed ->
        "Couldn't re-decode the VIN. Check you're online - NHTSA vPIC needs a network " +
            "connection - and try again."
    is VinRefreshResult.Decoded -> when (val identity = result.identity) {
        is IdentityWriteResult.Applied ->
            "Filled in ${identity.changedFields.joinToString(", ")} from the VIN."
        IdentityWriteResult.NothingToDo ->
            "Already matches what's on file. Nothing to change."
        is IdentityWriteResult.Conflict ->
            "Conflict, nothing changed: " + identity.fields.joinToString("; ") {
                "${it.field} on file is \"${it.onFile}\", the VIN says \"${it.decoded}\""
            } + ". Correct it by hand under Cars if the VIN is right."
        IdentityWriteResult.Unusable ->
            "The decode didn't return enough (year, make, and model) to act on."
        IdentityWriteResult.NoSuchVehicle ->
            "This car isn't registered yet, so there's no row to write the identity onto."
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Specs: VIN decoded", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewSpecsDecoded() = LegionTheme {
    VehicleSpecsContent(
        spec = VehicleSpec(
            vehicleId = "default",
            vin = "1J4FF48S5YL123456",
            engineCylinders = 6,
            displacementL = 4.0,
            engineHp = 190,
            engineConfig = "In-Line",
            fuelType = "Gasoline",
            transmissionStyle = "Automatic",
            transmissionSpeeds = "4-Speed",
            driveType = "4WD",
            bodyClass = "Sport Utility Vehicle (SUV)",
            doors = 4,
            series = "Sport",
            manufacturer = "CHRYSLER",
            plantCity = "Toledo",
            plantCountry = "UNITED STATES (USA)",
            decodedAt = 1785153923212L,
        ),
        loaded = true, connected = true, reading = false, failure = null,
        reconciling = false, reconcileMessage = null,
        onBack = {}, onCopyVin = {}, onReadVin = {}, onReconcileIdentity = {},
    )
}

@Preview(name = "Specs: no VIN, no adapter", widthDp = 360, heightDp = 500)
@Composable
private fun PreviewSpecsEmpty() = LegionTheme {
    VehicleSpecsContent(
        spec = null, loaded = true, connected = false, reading = false, failure = null,
        reconciling = false, reconcileMessage = null,
        onBack = {}, onCopyVin = {}, onReadVin = {}, onReconcileIdentity = {},
    )
}

@Preview(name = "Specs: read failed", widthDp = 360, heightDp = 500)
@Composable
private fun PreviewSpecsFailed() = LegionTheme {
    VehicleSpecsContent(
        spec = null, loaded = true, connected = true, reading = false,
        failure = "No usable VIN came back. Cars built before roughly 2001 often do not report " +
            "one over OBD-II at all, and the decode also needs a network connection.",
        reconciling = false, reconcileMessage = null,
        onBack = {}, onCopyVin = {}, onReadVin = {}, onReconcileIdentity = {},
    )
}

/**
 * The reconcile-specific outcome ticket 04 exists to surface: `vehicle_specs` had a VIN, the
 * decode disagreed with a driver-entered field, and nothing was written anywhere - see
 * [IdentityWriteResult.Conflict]'s doc for why a conflict on one field blocks the whole write.
 */
@Preview(name = "Specs: reconcile found a conflict", widthDp = 360, heightDp = 500)
@Composable
private fun PreviewSpecsReconcileConflict() = LegionTheme {
    VehicleSpecsContent(
        spec = VehicleSpec(vehicleId = "default", vin = "1FAKEVIN000000001", decodedAt = 1785153923212L),
        loaded = true, connected = false, reading = false, failure = null,
        reconciling = false,
        reconcileMessage = "Conflict, nothing changed: model on file is \"Cherokee Sport\", the VIN " +
            "says \"Cherokee\". Correct it by hand under Cars if the VIN is right.",
        onBack = {}, onCopyVin = {}, onReadVin = {}, onReconcileIdentity = {},
    )
}
