package com.kevin.midnightai.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.service.DebugSettings
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.vehicle.DiscoveredDevice
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdDeviceRegistry
import com.kevin.midnightai.vehicle.TcpTransport
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A device seen during discovery, with its friendly name captured at emission
 * time. BluetoothDevice.equals compares by MAC only, so a bare Set<BluetoothDevice>
 * never looked "changed" to Compose when a later sighting resolved the name -
 * the row stayed "OBD Device" forever. Carrying the name in the value makes the
 * state structurally different once the name arrives, forcing the recompose.
 * [isBle] mirrors [com.kevin.midnightai.vehicle.DiscoveredDevice.isBle] - which
 * scanner found it, and therefore whether it needs SELECT (BLE, never bonds)
 * or PAIR (classic).
 */
private data class NearbyDevice(val device: BluetoothDevice, val name: String?, val isBle: Boolean)

/**
 * Folds a freshly [DiscoveredDevice] sighting into the running nearby-device
 * map. The same physical dongle can be seen by both scanners (a dual-mode
 * chip, or a classic dongle that also happens to advertise) - when that
 * happens, the classic sighting always wins, since a device only NEEDS the
 * BLE path when it can't be bonded at all. Concretely: never let a BLE
 * sighting overwrite an entry already known to be classic.
 */
private fun foldDiscovered(
    current: Map<String, NearbyDevice>,
    discovered: DiscoveredDevice,
): Map<String, NearbyDevice> {
    val device = discovered.device
    val existing = current[device.address]
    val shouldUpdate = existing == null || existing.isBle || !discovered.isBle
    return if (shouldUpdate) {
        current + (device.address to NearbyDevice(device, nameOf(device), discovered.isBle))
    } else {
        current
    }
}

private fun nameOf(device: BluetoothDevice): String? =
    try { device.name } catch (e: SecurityException) { null }

@Composable
fun ObdDeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sizeClass = LocalSizeClass.current
    val outerPadding = if (sizeClass == SizeClass.EXPANDED) 20.dp else 12.dp
    val sectionGap = if (sizeClass == SizeClass.EXPANDED) 24.dp else 16.dp

    val scope = rememberCoroutineScope()
    val connectionState by ObdBluetoothManager.connectionState.collectAsState()
    val adapterInfo by ObdBluetoothManager.adapterInfo.collectAsState()
    // State, not a plain read: selecting a device must move the ACTIVE badge
    // immediately. (Reassigning pairedDevices with an equal list doesn't
    // recompose - Compose's structural equality sees no change.)
    var activeMac by remember { mutableStateOf(ObdBluetoothManager.getActiveDeviceMac(context)) }

    var pairedDevices by remember { mutableStateOf(ObdBluetoothManager.listBondedObd()) }
    var nearbyDevices by remember { mutableStateOf(mapOf<String, NearbyDevice>()) }
    var scanning by remember { mutableStateOf(false) }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val hasPermissions = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var permissionGranted by remember { mutableStateOf(hasPermissions) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            launcher.launch(requiredPermissions.toTypedArray())
        }
    }

    // The initial paired list was read at composition time, possibly BEFORE the
    // grant - re-read it once the permission lands or PAIRED stays empty until
    // the next visit.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) pairedDevices = ObdBluetoothManager.listBondedObd()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(outerPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "‹ BACK",
                style = MaterialTheme.typography.labelSmall,
                color = AriaColors.Amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onBack() }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("OBD DONGLES", style = AriaType.neonLabel, color = AriaColors.NeonMagenta)
        Spacer(Modifier.height(16.dp))

        // Debug-only: connect to the Ircama ELM327 emulator (TCP) instead of
        // scanning for a real dongle, so PID/DTC/tool-call code can be
        // exercised before a real drive. Unreachable in a release build.
        if (BuildConfig.DEBUG) {
            var emulatorOn by remember { mutableStateOf(DebugSettings.obdEmulatorEnabled(context)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, AriaColors.NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use OBD emulator (DEBUG)", style = MaterialTheme.typography.bodySmall, color = AriaColors.NeonCyan)
                    Text(
                        "TCP to ${TcpTransport.DEFAULT_HOST}:${TcpTransport.DEFAULT_PORT} instead of a real dongle",
                        style = MaterialTheme.typography.bodySmall,
                        color = AriaColors.TextDim,
                    )
                }
                Switch(
                    checked = emulatorOn,
                    onCheckedChange = {
                        emulatorOn = it
                        DebugSettings.setObdEmulatorEnabled(context, it)
                        ObdBluetoothManager.forceReconnect()
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Connection Status Banner
        val bannerColor = when (connectionState) {
            ObdBluetoothManager.ConnectionState.CONNECTED -> AriaColors.Amber
            ObdBluetoothManager.ConnectionState.CONNECTING -> AriaColors.NeonCyan
            ObdBluetoothManager.ConnectionState.ERROR -> AriaColors.ScanRed
            else -> AriaColors.TextDim
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bannerColor.copy(alpha = 0.1f))
                .border(1.dp, bannerColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = connectionState.name,
                    style = AriaType.readout,
                    color = bannerColor
                )
                if (activeMac != null) {
                    Text(
                        text = "Active: $activeMac",
                        style = MaterialTheme.typography.bodySmall,
                        color = AriaColors.TextDim
                    )
                }
                // What the adapter/car reported this connection - ATI identity
                // string + the OBD protocol the ECU actually negotiated. This
                // is what a capability test drive is here to capture.
                if (connectionState == ObdBluetoothManager.ConnectionState.CONNECTED && adapterInfo != null) {
                    Text(
                        text = adapterInfo!!.idString,
                        style = MaterialTheme.typography.bodySmall,
                        color = AriaColors.TextPrimary
                    )
                    Text(
                        text = adapterInfo!!.protocolName,
                        style = MaterialTheme.typography.bodySmall,
                        color = AriaColors.TextPrimary
                    )
                }
            }
        }

        Spacer(Modifier.height(sectionGap))

        if (!permissionGranted) {
            Text(
                "Bluetooth and location permissions are required to manage OBD devices.",
                color = AriaColors.ScanRed,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = { launcher.launch(requiredPermissions.toTypedArray()) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("GRANT PERMISSIONS")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("PAIRED", style = AriaType.neonLabel, color = AriaColors.TextDim)
                }

                items(pairedDevices) { device ->
                    DeviceRow(
                        device = device,
                        isActive = device.address == activeMac,
                        lastKnownInfo = ObdDeviceRegistry.getLastKnownInfo(context, device.address),
                        onSelect = {
                            ObdBluetoothManager.setActiveDevice(context, device.address)
                            activeMac = device.address
                            pairedDevices = ObdBluetoothManager.listBondedObd() // refresh list
                        },
                        onUnpair = {
                            ObdBluetoothManager.unpair(device)
                            if (device.address == activeMac) {
                                ObdBluetoothManager.setActiveDevice(context, null)
                                activeMac = null
                            }
                            pairedDevices = ObdBluetoothManager.listBondedObd()
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("NEARBY", style = AriaType.neonLabel, color = AriaColors.TextDim)
                        if (scanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AriaColors.Amber)
                        } else {
                            Text(
                                "SCAN",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, AriaColors.Amber, RoundedCornerShape(4.dp))
                                    .clickable {
                                        scanning = true
                                        nearbyDevices = emptyMap()
                                        scope.launch {
                                            ObdBluetoothManager.startDiscovery(context).collect { discovered ->
                                                nearbyDevices = foldDiscovered(nearbyDevices, discovered)
                                            }
                                            scanning = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = AriaColors.Amber
                            )
                        }
                    }
                }

                item { ObdScanHint() }

                items(
                    nearbyDevices.values.toList()
                        .filter { d -> pairedDevices.none { it.address == d.device.address } }
                        .sortedByDescending { ObdBluetoothManager.looksLikeObd(it.device) }
                ) { nearby ->
                    DiscoveryRow(
                        device = nearby.device,
                        name = nearby.name,
                        isBle = nearby.isBle,
                        onAction = {
                            if (nearby.isBle) {
                                // BLE serial dongles never bond - selecting is
                                // the whole flow, no PIN/pairing step exists.
                                ObdBluetoothManager.setActiveDevice(context, nearby.device.address, isBle = true)
                                activeMac = nearby.device.address
                            } else {
                                scope.launch {
                                    val success = ObdBluetoothManager.bondDevice(context, nearby.device)
                                    if (success) {
                                        // Auto-select the just-paired dongle so the
                                        // connection loop grabs it immediately, instead
                                        // of the driver having to pair then hunt for it
                                        // in PAIRED and tap again.
                                        ObdBluetoothManager.setActiveDevice(context, nearby.device.address)
                                        activeMac = nearby.device.address
                                        pairedDevices = ObdBluetoothManager.listBondedObd()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    isActive: Boolean,
    lastKnownInfo: String?,
    onSelect: () -> Unit,
    onUnpair: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (isActive) AriaColors.Amber else AriaColors.PanelBorder,
                RoundedCornerShape(10.dp)
            )
            .background(AriaColors.Panel.copy(alpha = 0.55f))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium, color = AriaColors.TextPrimary)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
            if (lastKnownInfo != null) {
                Text("Last seen: $lastKnownInfo", style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
            }
        }
        if (isActive) {
            Text("ACTIVE", style = AriaType.stamp, color = AriaColors.Amber, modifier = Modifier.padding(end = 12.dp))
        }
        Text(
            "UNPAIR",
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, AriaColors.ScanRed, RoundedCornerShape(4.dp))
                .clickable { onUnpair() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.ScanRed
        )
    }
}

/**
 * Dim hint under the NEARBY header. The top scan failures on a real head unit
 * are all invisible to the app: Location off (Android requires it for a BT
 * scan even with the permission granted), the dongle already grabbed by a
 * paired phone, or the dongle just not powered. It also reminds the driver the
 * dongle can show up under any name now that we no longer name-filter, and
 * that BLE ("for iPhone") dongles need SELECT rather than a PIN.
 */
@Composable
private fun ObdScanHint() {
    Text(
        "Not seeing your dongle? Turn on Location (Android needs it to scan), make sure the dongle " +
            "is plugged in and not already connected to your phone, then SCAN again. It can appear " +
            "under any name - tap PAIR for a classic dongle, or SELECT for a BLE one (no PIN needed).",
        style = MaterialTheme.typography.bodySmall,
        color = AriaColors.TextDim,
    )
}

/**
 * A row for an unpaired nearby dongle. Classic dongles need PAIR (bonding,
 * with the default-PIN auto-reply in [ObdBluetoothManager.bondDevice]); BLE
 * serial dongles never bond at all, so they get SELECT instead - tapping it
 * is the entire connection flow.
 */
@Composable
private fun DiscoveryRow(
    device: BluetoothDevice,
    name: String?,
    isBle: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(10.dp))
            .background(AriaColors.Panel.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name ?: "Unnamed device", style = MaterialTheme.typography.titleMedium, color = AriaColors.TextPrimary)
                if (isBle) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "BLE",
                        style = AriaType.stamp,
                        color = AriaColors.NeonCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, AriaColors.NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
        }
        Text(
            if (isBle) "SELECT" else "PAIR",
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, AriaColors.Amber, RoundedCornerShape(4.dp))
                .clickable { onAction() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.Amber
        )
    }
}

/**
 * OBD adapter-pairing step for onboarding (see ConversationalOnboardingScreen).
 * Same scan/pair/select flow as [ObdDeviceScreen] (the Settings version) -
 * shares its [DeviceRow]/[DiscoveryRow] - but framed as an optional wizard
 * step: SKIP is always available (pairing a dongle shouldn't gate finishing
 * setup - a driver without it handy yet, or testing indoors, should still be
 * able to finish), and CONTINUE advances whether or not anything paired.
 */
@Composable
fun ObdPairOnboardingStep(onContinue: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val sizeClass = LocalSizeClass.current
    val outerPadding = if (sizeClass == SizeClass.EXPANDED) 20.dp else 12.dp
    val sectionGap = if (sizeClass == SizeClass.EXPANDED) 24.dp else 16.dp

    val scope = rememberCoroutineScope()
    val connectionState by ObdBluetoothManager.connectionState.collectAsState()
    val adapterInfo by ObdBluetoothManager.adapterInfo.collectAsState()
    // State for the same reason as ObdDeviceScreen: the ACTIVE badge must move
    // on select without waiting for an unrelated recomposition.
    var activeMac by remember { mutableStateOf(ObdBluetoothManager.getActiveDeviceMac(context)) }

    var pairedDevices by remember { mutableStateOf(ObdBluetoothManager.listBondedObd()) }
    var nearbyDevices by remember { mutableStateOf(mapOf<String, NearbyDevice>()) }
    var scanning by remember { mutableStateOf(false) }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val hasPermissions = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var permissionGranted by remember { mutableStateOf(hasPermissions) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissionGranted = results.values.all { it } }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(requiredPermissions.toTypedArray())
    }

    // Same stale-list fix as ObdDeviceScreen: the composition-time read may
    // have run before the grant.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) pairedDevices = ObdBluetoothManager.listBondedObd()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(outerPadding),
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onBack() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
        )

        Spacer(Modifier.height(10.dp))
        Text("CONNECT YOUR CAR", style = AriaType.neonLabel, color = AriaColors.NeonMagenta)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pair your OBD-II dongle now so live engine data works from your first drive. You can " +
                "always do this later from Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(16.dp))

        val bannerColor = when (connectionState) {
            ObdBluetoothManager.ConnectionState.CONNECTED -> AriaColors.Amber
            ObdBluetoothManager.ConnectionState.CONNECTING -> AriaColors.NeonCyan
            ObdBluetoothManager.ConnectionState.ERROR -> AriaColors.ScanRed
            else -> AriaColors.TextDim
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bannerColor.copy(alpha = 0.1f))
                .border(1.dp, bannerColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(text = connectionState.name, style = AriaType.readout, color = bannerColor)
                if (connectionState == ObdBluetoothManager.ConnectionState.CONNECTED && adapterInfo != null) {
                    Text(adapterInfo!!.idString, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextPrimary)
                    Text(adapterInfo!!.protocolName, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(sectionGap))

        if (!permissionGranted) {
            Text(
                "Bluetooth and location permissions are needed to find your dongle.",
                color = AriaColors.ScanRed,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { launcher.launch(requiredPermissions.toTypedArray()) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("GRANT PERMISSIONS")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pairedDevices.isNotEmpty()) {
                    item { Text("PAIRED", style = AriaType.neonLabel, color = AriaColors.TextDim) }
                    items(pairedDevices) { device ->
                        DeviceRow(
                            device = device,
                            isActive = device.address == activeMac,
                            lastKnownInfo = ObdDeviceRegistry.getLastKnownInfo(context, device.address),
                            onSelect = {
                                ObdBluetoothManager.setActiveDevice(context, device.address)
                                activeMac = device.address
                                pairedDevices = ObdBluetoothManager.listBondedObd()
                            },
                            onUnpair = {
                                ObdBluetoothManager.unpair(device)
                                if (device.address == activeMac) {
                                    ObdBluetoothManager.setActiveDevice(context, null)
                                    activeMac = null
                                }
                                pairedDevices = ObdBluetoothManager.listBondedObd()
                            }
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("NEARBY", style = AriaType.neonLabel, color = AriaColors.TextDim)
                        if (scanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AriaColors.Amber)
                        } else {
                            Text(
                                "SCAN",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, AriaColors.Amber, RoundedCornerShape(4.dp))
                                    .clickable {
                                        scanning = true
                                        nearbyDevices = emptyMap()
                                        scope.launch {
                                            ObdBluetoothManager.startDiscovery(context).collect { discovered ->
                                                nearbyDevices = foldDiscovered(nearbyDevices, discovered)
                                            }
                                            scanning = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = AriaColors.Amber,
                            )
                        }
                    }
                }

                item { ObdScanHint() }

                items(
                    nearbyDevices.values.toList()
                        .filter { d -> pairedDevices.none { it.address == d.device.address } }
                        .sortedByDescending { ObdBluetoothManager.looksLikeObd(it.device) }
                ) { nearby ->
                    DiscoveryRow(
                        device = nearby.device,
                        name = nearby.name,
                        isBle = nearby.isBle,
                        onAction = {
                            if (nearby.isBle) {
                                ObdBluetoothManager.setActiveDevice(context, nearby.device.address, isBle = true)
                                activeMac = nearby.device.address
                            } else {
                                scope.launch {
                                    val success = ObdBluetoothManager.bondDevice(context, nearby.device)
                                    if (success) {
                                        ObdBluetoothManager.setActiveDevice(context, nearby.device.address)
                                        activeMac = nearby.device.address
                                        pairedDevices = ObdBluetoothManager.listBondedObd()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onSkip) { Text("SKIP FOR NOW", color = AriaColors.TextDim) }
            Button(onClick = onContinue) { Text("CONTINUE") }
        }
    }
}
