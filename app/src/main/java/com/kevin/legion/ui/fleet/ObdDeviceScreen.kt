package com.kevin.legion.ui.fleet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.BuildConfig
import com.kevin.legion.service.DebugSettings
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.vehicle.DiscoveredDevice
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdDeviceRegistry
import com.kevin.legion.vehicle.TcpTransport
import kotlinx.coroutines.launch

/**
 * ADAPTER - FLEET's fourth in-screen drilldown: scan for, pair, and select the
 * OBD-II dongle. Ported from Midnight AI's `ui/ObdDeviceScreen.kt` (the port on
 * 2026-07-31 carried [ObdBluetoothManager]'s scan/bond/select API and
 * [com.kevin.legion.vehicle.BleTransport] across but left every caller behind,
 * so nothing in LEGION ever reached them) and re-skinned onto the MILSPEC deck
 * primitives.
 *
 * **Why this screen is load-bearing rather than convenience.** Without it the
 * only path to a dongle is [ObdBluetoothManager]'s auto fallback, which searches
 * `bondedDevices`. A BLE serial clone (the HM-10-style "for iPhone" ELM327,
 * e.g. the V020) never bonds - no PIN, no SDP record - so it is never in that
 * list and never in Android's own Bluetooth settings either. It can only be
 * reached by scanning for its advertisement and persisting
 * [ObdDeviceRegistry.setBle], which is a decision only a picker can make. A
 * BLE dongle is unreachable by construction until this screen selects it.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill:
 * [ObdDeviceScreen] owns the permission launcher, the scan flow, the bond
 * coroutine and the registry writes; [ObdDeviceContent] is plain state plus
 * callbacks and is what the `@Preview`s below exercise (CLAUDE.md §8 L11 - the
 * previews are the gate, and they are rendered here rather than promised).
 */

/**
 * One row in PAIRED or NEARBY, flattened off [BluetoothDevice] at collection
 * time. Deliberately NOT the platform type: `BluetoothDevice.equals` compares by
 * MAC only, so a set of them never looked "changed" to Compose when a later
 * sighting resolved the name and the row stayed unnamed forever (the Midnight
 * bug this data class was introduced to fix). Flattening also keeps
 * [ObdDeviceContent] previewable - a `BluetoothDevice` cannot be constructed in
 * a preview.
 */
data class ObdDeviceRow(
    val mac: String,
    val name: String?,
    /**
     * Answered a classic inquiry, so PAIR is worth offering. NOT a promise that
     * bonding will succeed - the V020 answers inquiry with an audio device class
     * it cannot honour and then times out every bond.
     */
    val seenClassic: Boolean,
    /** Seen advertising over BLE, so it can be driven over GATT with no pairing step. */
    val seenBle: Boolean,
    /** Name matches [ObdBluetoothManager.looksLikeObd] - drives ordering, never visibility. */
    val looksLikeObd: Boolean,
    /** ATI identity + negotiated protocol from the last connect, or null if never connected. */
    val lastKnownInfo: String? = null,
) {
    /** Both scanners found it: offer PAIR and USE BLE side by side, and let the driver pick. */
    val isDualMode: Boolean get() = seenClassic && seenBle
}

data class ObdDeviceUiState(
    val permissionGranted: Boolean = false,
    val connectionState: ObdBluetoothManager.ConnectionState = ObdBluetoothManager.ConnectionState.DISCONNECTED,
    val adapterIdString: String? = null,
    val adapterProtocol: String? = null,
    val activeMac: String? = null,
    val paired: List<ObdDeviceRow> = emptyList(),
    val nearby: List<ObdDeviceRow> = emptyList(),
    val scanning: Boolean = false,
    /** MAC currently mid-bond, so its row can say so instead of looking inert for 30s. */
    val bondingMac: String? = null,
    /** MAC whose last PAIR attempt failed - said in words on the row, never by colour alone. */
    val bondFailedMac: String? = null,
    /** DEBUG builds only: TCP to the Ircama ELM327 emulator instead of a real dongle. */
    val emulatorOn: Boolean = false,
    val emulatorAvailable: Boolean = BuildConfig.DEBUG,
)

/**
 * Folds a fresh [DiscoveredDevice] sighting into the running nearby map,
 * accumulating what each scanner saw rather than letting either one win.
 *
 * **This replaces a rule that made a real dongle unreachable.** The first
 * version resolved a double sighting by declaring "classic always wins", on the
 * reasoning that a device only needs the BLE path when it cannot bond at all.
 * The V020 breaks that reasoning: it answers classic inquiry AND advertises over
 * BLE, and its bond times out every time (Kevin's phone, 2026-08-09: eight
 * attempts, each torn down ~26s in, `Bonded devices:` empty throughout). The
 * classic sighting overwrote the BLE one, the row offered PAIR, pairing failed,
 * and the working GATT path could not be reached - the exact "unreachable by
 * construction" failure this screen was built to end, reintroduced one commit
 * later by the tie-breaker.
 *
 * A sighting is evidence, not a verdict. Flags only ever go from false to true,
 * and a resolved name never regresses to null, so the row converges as sightings
 * arrive instead of flapping between scanners. Returns [current] unchanged when
 * nothing new was learned, so an idle scan does not churn recomposition.
 */
internal fun foldDiscovered(
    current: Map<String, ObdDeviceRow>,
    discovered: ObdDeviceRow,
): Map<String, ObdDeviceRow> {
    val existing = current[discovered.mac] ?: return current + (discovered.mac to discovered)
    val merged = existing.copy(
        name = existing.name ?: discovered.name,
        seenClassic = existing.seenClassic || discovered.seenClassic,
        seenBle = existing.seenBle || discovered.seenBle,
        looksLikeObd = existing.looksLikeObd || discovered.looksLikeObd,
    )
    return if (merged == existing) current else current + (discovered.mac to merged)
}

/**
 * NEARBY ordering: likely-OBD names first, then anything already named, then
 * the rest by MAC so the list is stable across sightings rather than reshuffling
 * every time a name resolves.
 */
internal fun sortNearby(rows: Collection<ObdDeviceRow>, pairedMacs: Set<String>): List<ObdDeviceRow> =
    rows.filter { it.mac !in pairedMacs }
        .sortedWith(
            compareByDescending<ObdDeviceRow> { it.looksLikeObd }
                .thenByDescending { it.name != null }
                .thenBy { it.mac },
        )

@Composable
fun ObdDeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionState by ObdBluetoothManager.connectionState.collectAsStateWithLifecycle()
    val adapterInfo by ObdBluetoothManager.adapterInfo.collectAsStateWithLifecycle()

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permissionGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> permissionGranted = results.values.all { it } }

    // The live BluetoothDevice handles behind the flattened rows, keyed by MAC.
    // The UI never sees these; every callback below looks one up by the MAC the
    // row carries.
    val devicesByMac = remember { mutableMapOf<String, BluetoothDevice>() }

    var activeMac by remember { mutableStateOf(ObdBluetoothManager.getActiveDeviceMac(context)) }
    var paired by remember { mutableStateOf(emptyList<ObdDeviceRow>()) }
    var nearbyMap by remember { mutableStateOf(emptyMap<String, ObdDeviceRow>()) }
    var scanning by remember { mutableStateOf(false) }
    var bondingMac by remember { mutableStateOf<String?>(null) }
    var bondFailedMac by remember { mutableStateOf<String?>(null) }
    var emulatorOn by remember { mutableStateOf(DebugSettings.obdEmulatorEnabled(context)) }

    fun refreshPaired() {
        val bonded = ObdBluetoothManager.listBondedObd()
        bonded.forEach { devicesByMac[it.address] = it }
        paired = bonded.map { device ->
            ObdDeviceRow(
                mac = device.address,
                name = safeName(device),
                // A bonded device is classic by definition - a BLE serial clone
                // never bonds, which is the whole reason this screen exists.
                seenClassic = true,
                seenBle = false,
                looksLikeObd = ObdBluetoothManager.looksLikeObd(device),
                lastKnownInfo = ObdDeviceRegistry.getLastKnownInfo(context, device.address),
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(requiredPermissions)
    }

    // The paired list must be read AFTER the grant lands: listBondedObd throws
    // SecurityException without BLUETOOTH_CONNECT and returns empty, and a
    // composition-time read can easily beat the launcher.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) refreshPaired()
    }

    ObdDeviceContent(
        state = ObdDeviceUiState(
            permissionGranted = permissionGranted,
            connectionState = connectionState,
            adapterIdString = adapterInfo?.idString,
            adapterProtocol = adapterInfo?.protocolName,
            activeMac = activeMac,
            paired = paired,
            nearby = sortNearby(nearbyMap.values, paired.map { it.mac }.toSet()),
            scanning = scanning,
            bondingMac = bondingMac,
            bondFailedMac = bondFailedMac,
            emulatorOn = emulatorOn,
        ),
        onBack = onBack,
        onGrantPermissions = { launcher.launch(requiredPermissions) },
        onScan = {
            if (scanning) return@ObdDeviceContent
            scanning = true
            nearbyMap = emptyMap()
            bondFailedMac = null
            scope.launch {
                ObdBluetoothManager.startDiscovery(context).collect { discovered: DiscoveredDevice ->
                    devicesByMac[discovered.device.address] = discovered.device
                    // The scanner's own name first: an unbonded BLE dongle has
                    // no cached device.name, so safeName alone left every BLE
                    // row reading UNNAMED DEVICE.
                    val name = discovered.advertisedName ?: safeName(discovered.device)
                    nearbyMap = foldDiscovered(
                        nearbyMap,
                        ObdDeviceRow(
                            mac = discovered.device.address,
                            name = name,
                            seenClassic = !discovered.isBle,
                            seenBle = discovered.isBle,
                            looksLikeObd = ObdBluetoothManager.looksLikeObd(name),
                        ),
                    )
                }
                scanning = false
            }
        },
        onSelect = { row, useBle ->
            // BLE serial dongles never bond - selecting IS the entire flow, and
            // the flag persisted here is what routes the connection loop to
            // BleTransport after a reboot (ObdDeviceRegistry.isBle's KDoc).
            // useBle comes from the button the driver actually pressed, not from
            // a guess about the device: a dual-mode dongle offers both, because
            // answering a classic inquiry does not mean it can complete a bond.
            ObdBluetoothManager.setActiveDevice(context, row.mac, isBle = useBle)
            activeMac = row.mac
            bondFailedMac = null
            refreshPaired()
            // Selecting is only a registry write; without this the loop keeps
            // retrying whatever it was already on until its next backoff tick.
            ObdBluetoothManager.forceReconnect()
        },
        onPair = { row ->
            val device = devicesByMac[row.mac] ?: return@ObdDeviceContent
            bondingMac = row.mac
            bondFailedMac = null
            scope.launch {
                val bonded = ObdBluetoothManager.bondDevice(context, device)
                bondingMac = null
                if (bonded) {
                    // Auto-select the just-paired dongle so the connection loop
                    // grabs it immediately, rather than making the driver pair
                    // and then hunt for it in PAIRED to tap again.
                    ObdBluetoothManager.setActiveDevice(context, row.mac, isBle = false)
                    activeMac = row.mac
                    refreshPaired()
                    ObdBluetoothManager.forceReconnect()
                } else {
                    bondFailedMac = row.mac
                }
            }
        },
        onUnpair = { row ->
            val device = devicesByMac[row.mac] ?: return@ObdDeviceContent
            ObdBluetoothManager.unpair(device)
            if (row.mac == activeMac) {
                ObdBluetoothManager.setActiveDevice(context, null)
                activeMac = null
            }
            refreshPaired()
        },
        onEmulatorToggle = { on ->
            emulatorOn = on
            DebugSettings.setObdEmulatorEnabled(context, on)
            ObdBluetoothManager.forceReconnect()
        },
    )
}

@SuppressLint("MissingPermission")
private fun safeName(device: BluetoothDevice): String? =
    try { device.name } catch (e: SecurityException) { null }

/** Plain UI: [state] plus callbacks, no manager/registry reference - see the file doc comment. */
@Composable
fun ObdDeviceContent(
    state: ObdDeviceUiState,
    onBack: () -> Unit,
    onGrantPermissions: () -> Unit,
    onScan: () -> Unit,
    /** Second arg: route this adapter over BLE GATT rather than classic RFCOMM. */
    onSelect: (ObdDeviceRow, Boolean) -> Unit,
    onPair: (ObdDeviceRow) -> Unit,
    onUnpair: (ObdDeviceRow) -> Unit,
    onEmulatorToggle: (Boolean) -> Unit,
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
                "ADAPTER",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()

            if (!state.permissionGranted) {
                Text(
                    "Bluetooth scan and connect permission is required to find an OBD adapter. " +
                        "On Android 11 and below, Location must also be on - Android will not " +
                        "return any scan result without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
                TextButton(onClick = onGrantPermissions, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("GRANT PERMISSIONS", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "link-pane") { LinkPane(state) }

                if (state.emulatorAvailable) {
                    item(key = "emulator-pane") { EmulatorPane(state.emulatorOn, onEmulatorToggle) }
                }

                item(key = "paired-header") {
                    Spacer(Modifier.height(12.dp))
                    SectionHeader("PAIRED")
                }
                if (state.paired.isEmpty()) {
                    item(key = "paired-empty") {
                        Text(
                            "No paired adapters. A BLE adapter will never appear here - it does " +
                                "not pair at all. Scan below and SELECT it instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(state.paired, key = { "paired-${it.mac}" }) { row ->
                        PairedRow(
                            row = row,
                            isActive = row.mac == state.activeMac,
                            // A bonded row is classic by definition.
                            onSelect = { onSelect(row, false) },
                            onUnpair = { onUnpair(row) },
                        )
                    }
                }

                item(key = "nearby-header") {
                    Spacer(Modifier.height(20.dp))
                    NearbyHeader(scanning = state.scanning, onScan = onScan)
                }
                item(key = "scan-hint") { ScanHint() }

                items(state.nearby, key = { "nearby-${it.mac}" }) { row ->
                    NearbyRow(
                        row = row,
                        bonding = row.mac == state.bondingMac,
                        bondFailed = row.mac == state.bondFailedMac,
                        isActive = row.mac == state.activeMac,
                        onPair = { onPair(row) },
                        onUseBle = { onSelect(row, true) },
                    )
                }

                item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The link state, its selected adapter, and what the adapter actually reported
 * on the last successful connect (ATI identity + negotiated OBD protocol).
 *
 * The state word is spelled out in text rather than carried by colour alone -
 * same discipline as FLEET's `// LIVE` / `// NO LINK` opening line. ERROR does
 * NOT get a red tag: CLAUDE.md's ticket-03 rule reserves red for a failed
 * reconciliation gate, and a dongle that would not answer is not one.
 */
@Composable
private fun LinkPane(state: ObdDeviceUiState) {
    val sem = LocalLegionSemantics.current
    val connected = state.connectionState == ObdBluetoothManager.ConnectionState.CONNECTED
    DeckPane(header = "Link", headerAccent = if (connected) "CONNECTED" else null) {
        Text(
            when (state.connectionState) {
                ObdBluetoothManager.ConnectionState.CONNECTED -> "// CONNECTED"
                ObdBluetoothManager.ConnectionState.CONNECTING -> "// CONNECTING"
                ObdBluetoothManager.ConnectionState.ERROR -> "// LAST ATTEMPT FAILED"
                ObdBluetoothManager.ConnectionState.DISCONNECTED -> "// NO LINK"
            },
            style = LegionType.stamp,
            color = if (connected) sem.credit else sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        DeckRow(label = "Active adapter", value = state.activeMac ?: "NONE SELECTED")
        if (connected && state.adapterIdString != null) {
            DeckRow(label = "Reported as", value = state.adapterIdString)
        }
        if (connected && state.adapterProtocol != null) {
            DeckRow(label = "Protocol", value = state.adapterProtocol)
        }
        if (state.activeMac == null) {
            Text(
                "Nothing selected. Until an adapter is selected here, the connection loop only " +
                    "auto-detects paired classic adapters - it can never find a BLE one.",
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * DEBUG builds only: connect to the Ircama ELM327 emulator over TCP instead of
 * scanning for a real dongle, so PID/DTC/tool-call paths can be exercised
 * without a car. Gated on [BuildConfig.DEBUG] via
 * [ObdDeviceUiState.emulatorAvailable], so it is unreachable in a release build.
 */
@Composable
private fun EmulatorPane(emulatorOn: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = "Emulator", modifier = Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("USE OBD EMULATOR (DEBUG)", style = LegionType.stamp, color = sem.faint)
                Text(
                    "TCP to ${TcpTransport.DEFAULT_HOST}:${TcpTransport.DEFAULT_PORT} instead of a real adapter",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.ghost,
                )
            }
            Switch(checked = emulatorOn, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val sem = LocalLegionSemantics.current
    Text(
        text,
        style = LegionType.stamp,
        color = sem.faint,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NearbyHeader(scanning: Boolean, onScan: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("NEARBY", style = LegionType.stamp, color = sem.faint)
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SCANNING", style = LegionType.stamp, color = sem.faint)
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            DeckAction("SCAN", onClick = onScan)
        }
    }
}

/**
 * The dim hint under NEARBY. Every top scan failure is invisible to the app:
 * Location switched off (Android returns no scan result without it, permission
 * granted or not), the adapter already grabbed by another phone, or the adapter
 * simply not powered. It also says what PAIR and SELECT mean, because the
 * difference is not cosmetic - a BLE adapter has no PIN step at all.
 */
@Composable
private fun ScanHint() {
    val sem = LocalLegionSemantics.current
    Text(
        "Not seeing the adapter? Turn Location on (Android needs it to scan), check it is plugged " +
            "in and not already connected to another phone, then SCAN again. Cheap adapters " +
            "advertise under any name, so nothing is hidden here. Tap PAIR for a classic adapter; " +
            "tap SELECT for a BLE one, which has no PIN and never appears in Android's Bluetooth settings.",
        style = MaterialTheme.typography.bodySmall,
        color = sem.ghost,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun PairedRow(
    row: ObdDeviceRow,
    isActive: Boolean,
    onSelect: () -> Unit,
    onUnpair: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        DeckRow(
            label = row.name ?: "UNNAMED ADAPTER",
            value = row.mac,
            tag = if (isActive) { { DeckTag("ACTIVE", DeckTagStyle.INVERTED_GREEN) } } else null,
            modifier = Modifier.clickable(onClick = onSelect),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.lastKnownInfo?.let { "Last seen: $it" } ?: "Never connected",
                style = LegionType.stamp,
                color = sem.ghost,
                modifier = Modifier.weight(1f),
            )
            DeckAction("UNPAIR", onClick = onUnpair)
        }
    }
}

@Composable
private fun NearbyRow(
    row: ObdDeviceRow,
    bonding: Boolean,
    bondFailed: Boolean,
    isActive: Boolean,
    onPair: () -> Unit,
    onUseBle: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        DeckRow(
            label = row.name ?: "UNNAMED DEVICE",
            value = row.mac,
            tag = when {
                isActive -> { { DeckTag("ACTIVE", DeckTagStyle.INVERTED_GREEN) } }
                row.isDualMode -> { { DeckTag("BLE + CLASSIC", DeckTagStyle.OUTLINE_MUTED) } }
                row.seenBle -> { { DeckTag("BLE", DeckTagStyle.OUTLINE_MUTED) } }
                else -> null
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    bonding -> "Pairing - the adapter may take up to 30 seconds"
                    // Stated in words, not by colour. When the dongle also
                    // advertises, the useful next step is the BLE path, not
                    // Android's Bluetooth settings - settings runs the same
                    // bond that just failed and will fail there too.
                    bondFailed && row.seenBle ->
                        "Pairing failed. This adapter also advertises over BLE - press USE BLE, which needs no pairing."
                    bondFailed ->
                        "Pairing failed. If it uses a PIN other than 1234, pair it in Android's Bluetooth settings first."
                    row.isDualMode ->
                        "Answers both ways. Try PAIR first; if it fails, USE BLE needs no pairing."
                    row.seenBle -> "BLE adapter - no pairing step, USE BLE connects it"
                    else -> "Classic adapter - PAIR tries the default PIN 1234"
                },
                style = LegionType.stamp,
                color = sem.ghost,
                modifier = Modifier.weight(1f),
            )
            if (bonding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.seenClassic) DeckAction("PAIR", onClick = onPair)
                    if (row.seenBle) DeckAction("USE BLE", onClick = onUseBle)
                }
            }
        }
    }
}

/**
 * A bordered stamp button. Local to this screen on purpose: CLAUDE.md's
 * ticket-12 note is that a caller needing a sixth shared primitive is a
 * decision for Kevin, not a per-screen workaround - so this stays private here
 * rather than being promoted into `ui/common/`.
 */
@Composable
private fun DeckAction(text: String, onClick: () -> Unit) {
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

@Preview(name = "Adapter: nothing selected, BLE dongle found", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewObdBleFound() = LegionTheme {
    ObdDeviceContent(
        state = ObdDeviceUiState(
            permissionGranted = true,
            connectionState = ObdBluetoothManager.ConnectionState.DISCONNECTED,
            activeMac = null,
            paired = emptyList(),
            nearby = listOf(
                ObdDeviceRow(
                    "AA:BB:CC:11:22:33", "V020",
                    seenClassic = false, seenBle = true, looksLikeObd = true,
                ),
                ObdDeviceRow(
                    "DE:AD:BE:EF:00:01", null,
                    seenClassic = false, seenBle = true, looksLikeObd = false,
                ),
            ),
            emulatorAvailable = false,
        ),
        onBack = {}, onGrantPermissions = {}, onScan = {}, onSelect = { _, _ -> },
        onPair = {}, onUnpair = {}, onEmulatorToggle = {},
    )
}

/**
 * The real V020, as Kevin's phone actually sees it: both scanners find it, so
 * it offers PAIR and USE BLE side by side. Under the old model this row was
 * flattened to classic-only and the BLE path was unreachable.
 */
@Preview(name = "Adapter: dual-mode dongle, pairing failed", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewObdDualModeBondFailed() = LegionTheme {
    ObdDeviceContent(
        state = ObdDeviceUiState(
            permissionGranted = true,
            connectionState = ObdBluetoothManager.ConnectionState.DISCONNECTED,
            activeMac = null,
            paired = emptyList(),
            nearby = listOf(
                ObdDeviceRow(
                    "78:9A:BC:11:22:33", "V020",
                    seenClassic = true, seenBle = true, looksLikeObd = true,
                ),
            ),
            bondFailedMac = "78:9A:BC:11:22:33",
            emulatorAvailable = false,
        ),
        onBack = {}, onGrantPermissions = {}, onScan = {}, onSelect = { _, _ -> },
        onPair = {}, onUnpair = {}, onEmulatorToggle = {},
    )
}

@Preview(name = "Adapter: connected classic dongle", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewObdConnected() = LegionTheme {
    ObdDeviceContent(
        state = ObdDeviceUiState(
            permissionGranted = true,
            connectionState = ObdBluetoothManager.ConnectionState.CONNECTED,
            adapterIdString = "ELM327 v1.5",
            adapterProtocol = "ISO 15765-4 CAN (11-bit, 500k) (auto)",
            activeMac = "00:1D:A5:68:98:8B",
            paired = listOf(
                ObdDeviceRow(
                    "00:1D:A5:68:98:8B", "OBDII",
                    seenClassic = true, seenBle = false, looksLikeObd = true,
                    lastKnownInfo = "ELM327 v1.5 · ISO 15765-4 CAN (11-bit, 500k) (auto)",
                ),
            ),
            nearby = emptyList(),
            emulatorAvailable = false,
        ),
        onBack = {}, onGrantPermissions = {}, onScan = {}, onSelect = { _, _ -> },
        onPair = {}, onUnpair = {}, onEmulatorToggle = {},
    )
}

@Preview(name = "Adapter: scanning, pair failed", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewObdScanning() = LegionTheme {
    ObdDeviceContent(
        state = ObdDeviceUiState(
            permissionGranted = true,
            connectionState = ObdBluetoothManager.ConnectionState.ERROR,
            activeMac = "00:1D:A5:68:98:8B",
            paired = emptyList(),
            nearby = listOf(
                ObdDeviceRow(
                    "00:1D:A5:68:98:8B", "OBDII",
                    seenClassic = true, seenBle = false, looksLikeObd = true,
                ),
                ObdDeviceRow(
                    "11:22:33:44:55:66", "SPP-CA",
                    seenClassic = true, seenBle = false, looksLikeObd = false,
                ),
            ),
            scanning = true,
            bondFailedMac = "11:22:33:44:55:66",
            emulatorAvailable = false,
        ),
        onBack = {}, onGrantPermissions = {}, onScan = {}, onSelect = { _, _ -> },
        onPair = {}, onUnpair = {}, onEmulatorToggle = {},
    )
}

@Preview(name = "Adapter: permission not granted", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewObdNoPermission() = LegionTheme {
    ObdDeviceContent(
        state = ObdDeviceUiState(permissionGranted = false, emulatorAvailable = false),
        onBack = {}, onGrantPermissions = {}, onScan = {}, onSelect = { _, _ -> },
        onPair = {}, onUnpair = {}, onEmulatorToggle = {},
    )
}
