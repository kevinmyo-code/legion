package com.kevin.legion.ui.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.kevin.legion.location.AirNow
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import kotlinx.coroutines.launch

/**
 * Current area name + AirNow air quality, on glass (command-center ticket 08). Location half
 * calls [LiveToolbox.resolveCurrentLocation] - the same function `get_current_location` resolves
 * against (widened `internal` for the drift-debt half of this same ticket) - never a second
 * Geocoder call re-deriving the permission/providers/fix branches. Air-quality half is this
 * ticket's own first consumer of [AirNow].
 *
 * **In-memory only, on-demand, no auto-poll** - same posture as [PackageCard]/[FlightCard]. **A
 * missing AQI reading is NEVER rendered as clean air** (the ticket's own rule, restated in
 * [aqiLine]): [AirNow.Reading.NoKey]/[Unreachable]/[NoData] each get their own worded line, never
 * collapsed into silence or into a false "Good".
 */
private sealed class AreaCardState {
    object Loading : AreaCardState()
    data class Ready(val locationLine: String, val aqi: AirNow.Reading, val fetchedAtMs: Long) : AreaCardState()
    /** The location half itself failed - no GPS fix, no permission, providers off. There is no
     * area to look air quality up FOR, so the whole card renders this one line rather than a
     * half-populated card with an area name it does not have. */
    data class LocationFailed(val message: String) : AreaCardState()
}

internal fun locationFailureMessage(readout: LiveToolbox.LocationReadout): String = when (readout) {
    LiveToolbox.LocationReadout.NoPermission -> "Location permission not granted - grant it to see the area."
    LiveToolbox.LocationReadout.ProvidersOff -> "Location services are off on the phone."
    LiveToolbox.LocationReadout.NoFix -> "No GPS fix yet - try again in a moment."
    is LiveToolbox.LocationReadout.Available -> error("Available is not a failure - caller must branch first")
}

/** The one line naming where "here" is, reused verbatim shape from
 * `ui/FleetScreen.kt`'s `currentLocationReadout` (both ultimately read the same
 * [LiveToolbox.LocationReadout.Available] fields) but without that screen's "Current location:"
 * prefix - this card's own [DeckPane] header already says where the reading is FROM. */
internal fun areaLine(available: LiveToolbox.LocationReadout.Available): String =
    available.label ?: "${available.coords} (couldn't resolve an address)"

/** [AirNow.Reading] rendered as one line. Every branch is worded, never blank - see this file's
 * header doc for why [NoKey]/[Unreachable]/[NoData] must never read as "Good". */
internal fun aqiLine(reading: AirNow.Reading): String = when (reading) {
    is AirNow.Reading.Ok -> "AQI ${reading.aqi} (${reading.category}) - ${reading.pollutant}, ${reading.reportingArea}"
    AirNow.Reading.NoKey -> "Air quality isn't set up on this build (no AirNow key)."
    AirNow.Reading.Unreachable -> "Couldn't reach AirNow to check air quality."
    AirNow.Reading.NoData -> "No AirNow station reports near here."
}

@Composable
fun AreaCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var state by remember { mutableStateOf<AreaCardState>(AreaCardState.Loading) }

    fun refresh() {
        state = AreaCardState.Loading
        scope.launch {
            when (val readout = LiveToolbox.resolveCurrentLocation(context)) {
                is LiveToolbox.LocationReadout.Available -> {
                    val aqi = AirNow.current(readout.lat, readout.lon)
                    state = AreaCardState.Ready(
                        locationLine = areaLine(readout),
                        aqi = aqi,
                        fetchedAtMs = System.currentTimeMillis(),
                    )
                }
                else -> state = AreaCardState.LocationFailed(locationFailureMessage(readout))
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    DeckPane(header = "Area", modifier = modifier) {
        when (val s = state) {
            is AreaCardState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text("Checking the area...", style = LegionType.stamp, color = sem.faint)
            }
            is AreaCardState.Ready -> {
                Text(s.locationLine, style = MaterialTheme.typography.bodySmall, color = sem.data)
                Text(
                    aqiLine(s.aqi),
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "fetched ${clockTime(s.fetchedAtMs)}",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            is AreaCardState.LocationFailed -> Text(s.message, style = LegionType.stamp, color = sem.faint)
        }
        TextButton(onClick = { refresh() }) { Text("REFRESH") }
    }
}
