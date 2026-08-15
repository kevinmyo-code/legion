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
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleSpecController
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
        onBack = onBack,
        onCopyVin = { vin -> copyToClipboard(context, vin) },
        onReadVin = {
            reading = true
            failure = null
            scope.launch {
                val ok = runCatching { VehicleSpecController.refreshFromObd(context) }
                    .getOrDefault(false)
                reading = false
                if (ok) {
                    reloadKey++
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
    onBack: () -> Unit,
    onCopyVin: (String) -> Unit,
    onReadVin: () -> Unit,
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

/** Bordered stamp button, local to this screen - same call as ObdDeviceScreen's DeckAction. */
@Composable
private fun SpecAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
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
        onBack = {}, onCopyVin = {}, onReadVin = {},
    )
}

@Preview(name = "Specs: no VIN, no adapter", widthDp = 360, heightDp = 500)
@Composable
private fun PreviewSpecsEmpty() = LegionTheme {
    VehicleSpecsContent(
        spec = null, loaded = true, connected = false, reading = false, failure = null,
        onBack = {}, onCopyVin = {}, onReadVin = {},
    )
}

@Preview(name = "Specs: read failed", widthDp = 360, heightDp = 500)
@Composable
private fun PreviewSpecsFailed() = LegionTheme {
    VehicleSpecsContent(
        spec = null, loaded = true, connected = true, reading = false,
        failure = "No usable VIN came back. Cars built before roughly 2001 often do not report " +
            "one over OBD-II at all, and the decode also needs a network connection.",
        onBack = {}, onCopyVin = {}, onReadVin = {},
    )
}
