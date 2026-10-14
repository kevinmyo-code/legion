package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdGauge

/**
 * Which surface a [GaugePickerSection] configures - each keeps its own selection.
 * Shared between [ControlPanelScreen] (Appearance settings) and [ObdSheet]'s MANAGE tab,
 * extracted 2026-07-16 (B30) so both call the same picker instead of two copies drifting apart.
 */
internal enum class GaugeSurface { CRUISE, LIGHTS_OUT }

/**
 * A per-surface OBD gauge picker: which readouts appear on either the Cruise
 * instrument strip or the Lights Out footer (each surface keeps its own,
 * separate selection). Only slow-changing gauges are offered (see [ObdGauge]) -
 * fast PIDs like RPM were removed because polling them every few seconds starves
 * the head unit's shared Bluetooth radio and stutters phone music. When the
 * adapter is connected the list is filtered to the PIDs the car actually
 * reported as supported ([ObdBluetoothManager.supportedPids]); while
 * disconnected it shows every option with a hint to connect for auto-detection.
 */
@Composable
internal fun GaugePickerSection(context: Context, surface: GaugeSurface) {
    // ObdBluetoothManager's static initializer touches BluetoothAdapter, which
    // throws NoClassDefFoundError in the preview JVM the first time the class
    // is touched at all - so even reading its StateFlow properties crashes in
    // inspection mode. Treat inspection mode as "no supported PIDs detected,
    // disconnected" (the real not-connected-yet state), same fallback the
    // runtime already renders while an adapter hasn't reported in yet.
    val inPreview = LocalInspectionMode.current
    val supported = if (inPreview) emptySet() else ObdBluetoothManager.supportedPids.collectAsState().value
    val isConnected = if (inPreview) {
        false
    } else {
        ObdBluetoothManager.connectionState.collectAsState().value == ObdBluetoothManager.ConnectionState.CONNECTED
    }
    fun read(): Set<String> = when (surface) {
        GaugeSurface.CRUISE -> CruiseSettings.enabledCruiseGaugeKeys(context)
        GaugeSurface.LIGHTS_OUT -> CruiseSettings.enabledLightsOutGaugeKeys(context)
    }
    // Local mirror of the persisted enabled set so the switches flip instantly.
    var enabled by remember { mutableStateOf(read()) }

    Text(
        if (isConnected) {
            "Detected from your car. Only slow-changing gauges are offered, so live data never " +
                "fights your Bluetooth music."
        } else {
            "Connect your OBD adapter to detect which gauges your car supports."
        },
        style = MaterialTheme.typography.bodySmall,
        color = AriaColors.TextDim,
    )
    Spacer(Modifier.height(10.dp))
    for (gauge in ObdGauge.entries.filter { it.isSupported(supported) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = gauge.key in enabled,
                onCheckedChange = { checked ->
                    when (surface) {
                        GaugeSurface.CRUISE -> CruiseSettings.setCruiseGaugeEnabled(context, gauge, checked)
                        GaugeSurface.LIGHTS_OUT -> CruiseSettings.setLightsOutGaugeEnabled(context, gauge, checked)
                    }
                    enabled = read()
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(gaugeDisplayName(gauge), style = MaterialTheme.typography.bodyMedium, color = AriaColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Full-word Settings label for each [ObdGauge] (the strip uses the terse caption). */
internal fun gaugeDisplayName(gauge: ObdGauge): String = when (gauge) {
    ObdGauge.COOLANT -> "Coolant temp"
    ObdGauge.VOLTS -> "Battery voltage"
    ObdGauge.FUEL -> "Fuel level"
    ObdGauge.INTAKE_AIR -> "Intake air temp"
    ObdGauge.LONG_FUEL_TRIM -> "Long fuel trim"
}
