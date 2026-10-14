package com.kevin.midnightai.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.bluetooth.LinkState
import com.kevin.midnightai.bluetooth.PhoneLinkCapability
import com.kevin.midnightai.bluetooth.PhoneLinkInfo
import com.kevin.midnightai.bluetooth.PhoneLinkManager
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.launch

/**
 * The two-phone Bluetooth switcher. Reads [PhoneLinkManager.state] live and dispatches every
 * action through it - this screen never calls a platform Bluetooth API directly. Built for the
 * driving-adjacent, parked use case (Setup / Control Panel), so touch targets follow
 * [AriaDimens.driveTouchMin] rather than a phone-density list.
 */
@Composable
internal fun BluetoothManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(context) {
        PhoneLinkManager.init(context)
        PhoneLinkManager.refresh()
        onDispose {}
    }
    val state by PhoneLinkManager.state.collectAsState()
    var confirmForget by remember { mutableStateOf<PhoneLinkInfo?>(null) }
    var busy by remember { mutableStateOf(false) }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { PhoneLinkManager.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(AriaDimens.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹ BACK",
                style = AriaType.neonLabel,
                color = AriaColors.Amber,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = AriaDimens.s2, horizontal = AriaDimens.s1),
            )
            Text(
                "PHONE LINK",
                style = AriaType.stamp.copy(fontSize = 16.sp),
                color = AriaColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(52.dp))
        }

        Spacer(Modifier.height(AriaDimens.s3))
        CapabilityBanner(state.capability)
        Spacer(Modifier.height(AriaDimens.s3))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AriaDimens.s2),
        ) {
            if (state.phones.isEmpty()) {
                Text(
                    if (state.capability == PhoneLinkCapability.VENDOR) {
                        "No phones visible to this app - they're paired through the vendor app below."
                    } else {
                        "No phones paired yet. Use PAIR NEW PHONE below."
                    },
                    style = AriaType.readout.copy(fontSize = 14.sp),
                    color = AriaColors.TextDim,
                )
            }
            state.phones.forEach { phone ->
                PhoneRow(
                    phone = phone,
                    capability = state.capability,
                    busy = busy,
                    onSwitchTo = {
                        busy = true
                        scope.launch {
                            PhoneLinkManager.switchTo(phone.address)
                            busy = false
                        }
                    },
                    onDisconnect = {
                        busy = true
                        scope.launch {
                            PhoneLinkManager.disconnect(phone.address)
                            busy = false
                        }
                    },
                    onForget = { confirmForget = phone },
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s2))
        state.lastResult?.let {
            Text(
                it,
                style = AriaType.readout.copy(fontSize = 13.sp),
                color = AriaColors.NeonCyan,
                modifier = Modifier.padding(bottom = AriaDimens.s2),
            )
        }

        FooterActions(
            vendorAvailable = state.vendorPackage != null,
            busy = busy,
            onPairNew = { discoverableLauncher.launch(PhoneLinkManager.pairNewPhoneIntent()) },
            onReset = {
                busy = true
                scope.launch {
                    PhoneLinkManager.resetBluetooth()
                    busy = false
                }
            },
            onSystemSettings = { context.startActivity(PhoneLinkManager.systemBluetoothSettingsIntent()) },
            onVendorApp = { launchVendorApp(context) },
        )
    }

    confirmForget?.let { phone ->
        ForgetConfirmDialog(
            phone = phone,
            onConfirm = {
                confirmForget = null
                busy = true
                scope.launch {
                    PhoneLinkManager.forget(phone.address)
                    busy = false
                }
            },
            onDismiss = { confirmForget = null },
        )
    }
}

private fun launchVendorApp(context: Context) {
    PhoneLinkManager.vendorAppLaunchIntent(context)?.let { context.startActivity(it) }
}

@Composable
private fun CapabilityBanner(capability: PhoneLinkCapability) {
    val (text, color) = when (capability) {
        PhoneLinkCapability.FULL ->
            "This head unit's Bluetooth is fully under our control. Switching works directly." to AriaColors.NeonCyan
        PhoneLinkCapability.VIEW_ONLY ->
            "We can see paired phones but can't switch them directly - use system Bluetooth settings." to AriaColors.Amber
        PhoneLinkCapability.VENDOR ->
            "Phones are managed by a separate Bluetooth app on this head unit, not by us." to AriaColors.Amber
        PhoneLinkCapability.NO_BLUETOOTH ->
            "No Bluetooth adapter or permission found on this head unit." to AriaColors.ScanRed
    }
    Text(text, style = AriaType.readout.copy(fontSize = 13.sp), color = color)
}

@Composable
private fun PhoneRow(
    phone: PhoneLinkInfo,
    capability: PhoneLinkCapability,
    busy: Boolean,
    onSwitchTo: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AriaColors.Panel, RoundedCornerShape(AriaDimens.pageCorner))
            .padding(AriaDimens.s2),
    ) {
        Text(
            phone.name + if (phone.preferred) "  (preferred)" else "",
            style = AriaType.neonLabel.copy(fontSize = 15.sp),
            color = AriaColors.TextPrimary,
        )
        Spacer(Modifier.height(AriaDimens.s1))
        Row {
            Text(
                "AUDIO: ${phone.audioState.label()}",
                style = AriaType.readout.copy(fontSize = 12.sp),
                color = phone.audioState.color(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "CALLS: ${phone.callsState.label()}",
                style = AriaType.readout.copy(fontSize = 12.sp),
                color = phone.callsState.color(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(AriaDimens.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            RowButton(
                "SWITCH TO",
                enabled = !busy && capability == PhoneLinkCapability.FULL && phone.audioState != LinkState.CONNECTED,
                onClick = onSwitchTo,
                modifier = Modifier.weight(1f),
            )
            RowButton(
                "DISCONNECT",
                enabled = !busy && capability == PhoneLinkCapability.FULL,
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            )
            RowButton(
                "FORGET",
                enabled = !busy,
                onClick = onForget,
                color = AriaColors.ScanRed,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = AriaColors.Amber,
) {
    Text(
        label,
        style = AriaType.stamp.copy(fontSize = 12.sp),
        color = if (enabled) color else AriaColors.TextDim,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(AriaColors.Background, RoundedCornerShape(AriaDimens.pageCorner))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = AriaDimens.s3, horizontal = AriaDimens.s1),
    )
}

@Composable
private fun FooterActions(
    vendorAvailable: Boolean,
    busy: Boolean,
    onPairNew: () -> Unit,
    onReset: () -> Unit,
    onSystemSettings: () -> Unit,
    onVendorApp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            RowButton("PAIR NEW PHONE", enabled = !busy, onClick = onPairNew, modifier = Modifier.weight(1f))
            RowButton("RESET BLUETOOTH", enabled = !busy, onClick = onReset, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            RowButton("SYSTEM SETTINGS", enabled = !busy, onClick = onSystemSettings, modifier = Modifier.weight(1f))
            if (vendorAvailable) {
                RowButton("VENDOR APP", enabled = !busy, onClick = onVendorApp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ForgetConfirmDialog(phone: PhoneLinkInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss)
            .padding(AriaDimens.s4),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .background(AriaColors.Panel, RoundedCornerShape(AriaDimens.pageCorner))
                .padding(AriaDimens.s3),
        ) {
            Text(
                "Forget \"${phone.name}\" on this head unit?",
                style = AriaType.neonLabel.copy(fontSize = 14.sp),
                color = AriaColors.TextPrimary,
            )
            Spacer(Modifier.height(AriaDimens.s2))
            Text(
                "The phone stays paired on its own side - you'd re-pair from there if you want it back.",
                style = AriaType.readout.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(AriaDimens.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
                RowButton("FORGET", enabled = true, onClick = onConfirm, color = AriaColors.ScanRed, modifier = Modifier.weight(1f))
                RowButton("CANCEL", enabled = true, onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun LinkState.label(): String = when (this) {
    LinkState.CONNECTED -> "connected"
    LinkState.CONNECTING -> "connecting"
    LinkState.DISCONNECTING -> "disconnecting"
    LinkState.DISCONNECTED -> "off"
    LinkState.UNKNOWN -> "unknown"
}

@Composable
private fun LinkState.color(): androidx.compose.ui.graphics.Color = when (this) {
    LinkState.CONNECTED -> AriaColors.NeonCyan
    LinkState.CONNECTING, LinkState.DISCONNECTING -> AriaColors.Amber
    LinkState.DISCONNECTED, LinkState.UNKNOWN -> AriaColors.TextDim
}
