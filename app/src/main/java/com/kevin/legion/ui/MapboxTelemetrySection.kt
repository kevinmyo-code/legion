package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.navigation.MapboxTelemetry
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The Mapbox usage-data switch on the Setup screen (mapbox-nav ticket 11, research 01 section 6): the
 * SDK's own telemetry opt-out. Its own file because `KeyScreen.kt` is past the 1000-line mark. The
 * switch shows what the SDK reports, read back after each change, never what was last tapped.
 */
@Composable
fun MapboxTelemetrySection() {
    val sem = LocalLegionSemantics.current
    var on by remember { mutableStateOf(MapboxTelemetry.isOn()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Share usage data with Mapbox",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = on == true,
                enabled = on != null,
                onCheckedChange = {
                    MapboxTelemetry.set(it)
                    on = MapboxTelemetry.isOn()
                },
            )
        }
        Text(MapboxTelemetrySectionCopy.explainer(on), style = LegionType.stamp, color = sem.faint)
    }
}

/** The row's sentences, outside the Composable so a test can pin them (the KeyScreen convention). */
internal object MapboxTelemetrySectionCopy {
    /** [on] is the SDK's own answer; null means it could not be read. */
    fun explainer(on: Boolean?): String = when (on) {
        null -> "Mapbox's usage-data setting could not be read on this phone, so it cannot be changed here."
        true -> "The map and navigation tools report anonymous usage to Mapbox. Turn this off to stop that. " +
            "Finding a route still sends your location to Mapbox."
        false -> "Off. The map and navigation tools do not report usage to Mapbox. " +
            "Finding a route still sends your location to Mapbox."
    }
}
