package com.kevin.legion.ui.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.field.CompassSource
import com.kevin.legion.field.FieldMath
import com.kevin.legion.field.FieldReadout
import com.kevin.legion.field.FieldUiState
import com.kevin.legion.field.GpsStatus
import com.kevin.legion.field.TurnaroundStatus
import com.kevin.legion.field.WaypointUi
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import java.time.ZoneId

/** What the screen can ask for. One holder so the restyle keeps the same wiring. */
data class FieldActions(
    val onBack: () -> Unit,
    val onGrantLocation: () -> Unit,
    val onMark: (String) -> Unit,
    val onMargin: (Int) -> Unit,
)

/**
 * The Field screen (round 5): compass, position, waypoints, sun and moon. Plain on purpose and
 * built from existing components; `feat/typeset-ui` restyles it by replacing these composables and
 * keeping [FieldUiState] and [FieldViewModel].
 */
@Composable
fun FieldContent(state: FieldUiState, actions: FieldActions, zone: ZoneId = ZoneId.systemDefault()) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckScreenHeader(title = "Field", onBack = actions.onBack)
        Text(
            "Works with no signal: compass, GPS, grid, sun and moon are all on this phone.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        CompassPane(state)
        Spacer(Modifier.height(10.dp))
        PositionPane(state, actions)
        Spacer(Modifier.height(10.dp))
        WaypointsPane(state, actions)
        Spacer(Modifier.height(10.dp))
        SunMoonPane(state, actions, zone)
    }
}

@Composable
private fun CompassPane(state: FieldUiState) {
    DeckPane(header = "Compass") {
        val h = state.heading
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompassDial(headingDeg = h?.trueDeg ?: h?.magneticDeg, modifier = Modifier.size(140.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                if (h == null) {
                    Text(
                        if (state.compassSource == CompassSource.NONE) {
                            "This phone has no compass sensor."
                        } else {
                            "Waiting for the compass..."
                        },
                    )
                } else {
                    Text("${FieldMath.degText(h.trueDeg ?: h.magneticDeg)}° ${h.point}", style = MaterialTheme.typography.headlineMedium)
                    Text("Magnetic ${FieldMath.degText(h.magneticDeg)}°" + (h.trueDeg?.let { "  True ${FieldMath.degText(it)}°" } ?: "  (true needs a GPS fix)"))
                    Text("Back azimuth ${FieldMath.degText(h.backAzimuthTrueDeg ?: h.backAzimuthMagDeg)}°" + if (h.backAzimuthTrueDeg != null) " true" else " magnetic")
                    h.northArrowDeg?.let { Text("True north: ${FieldReadout.northWords(it)}") }
                    Text(if (h.upright) "Held upright: sighting along the back" else "Held flat: the top edge points")
                }
                state.declinationText?.let { Text("Declination $it") }
                Text("Accuracy: ${state.compassAccuracy.words}")
            }
        }
        if (state.needsCalibration) {
            Text(
                "Calibrate: move the phone slowly in a figure 8, turning it over a few times, away from " +
                    "metal and the car.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "calibrate compass" },
            )
        }
    }
}

/** A plain dial: N marked, the needle pointing to north relative to the phone. */
@Composable
private fun CompassDial(headingDeg: Double?, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.semantics { contentDescription = "compass dial" }) {
        val r = size.minDimension / 2
        drawCircle(color, radius = r * 0.95f, style = Stroke(width = 3f))
        if (headingDeg == null) return@Canvas
        rotate(degrees = (-headingDeg).toFloat()) {
            drawLine(accent, center, Offset(center.x, center.y - r * 0.85f), strokeWidth = 8f)
            drawLine(color, center, Offset(center.x, center.y + r * 0.6f), strokeWidth = 4f)
        }
    }
}

@Composable
private fun PositionPane(state: FieldUiState, actions: FieldActions) {
    DeckPane(header = "Position (GPS only)") {
        when (state.gps) {
            GpsStatus.NO_PERMISSION -> {
                Text("LEGION has no location permission, so there is no GPS position.")
                DeckButton(text = "Allow location", onClick = actions.onGrantLocation, modifier = Modifier.fillMaxWidth())
            }
            GpsStatus.OFF -> Text("GPS is switched off in the phone's settings.")
            GpsStatus.SEARCHING -> Text("Searching for satellites. Open sky helps; this can take a minute.")
            GpsStatus.FIX -> Unit
        }
        state.position?.let { p ->
            Text(p.latLonText, style = MaterialTheme.typography.titleMedium)
            Text("MGRS ${p.mgrsText ?: "not available at this latitude"}")
            Text("UTM ${p.utmText ?: "not available at this latitude"}")
            Text("Altitude ${p.altitudeText}")
            Text("Accuracy: ${p.accuracyText}")
            Text("Fix ${FieldReadout.ageText(p.fixAgeMs)}")
        }
    }
}

@Composable
private fun WaypointsPane(state: FieldUiState, actions: FieldActions) {
    var name by remember { mutableStateOf("") }
    DeckPane(header = "Waypoints") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DeckButton(
                text = "Mark car here",
                onClick = { actions.onMark("car") },
                enabled = !state.markBusy && state.position != null,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name a spot") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        DeckButton(
            text = "Mark this spot",
            onClick = { actions.onMark(name); name = "" },
            enabled = !state.markBusy && state.position != null && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        state.message?.let { Text(it, modifier = Modifier.padding(top = 6.dp)) }
        if (state.position == null) Text("Bearings need a GPS fix.")
        state.waypoints.forEach { WaypointRow(it) }
    }
}

@Composable
private fun WaypointRow(w: WaypointUi) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Arrow(w.arrowDeg, Modifier.size(36.dp))
        Spacer(Modifier.size(8.dp))
        Column {
            Text(w.label + if (w.pending) " (on this phone, not synced yet)" else "", style = MaterialTheme.typography.titleSmall)
            Text(
                "${w.distanceText} straight line, bearing ${FieldMath.degText(w.bearingTrueDeg)}° true" +
                    (w.bearingMagDeg?.let { " / ${FieldMath.degText(it)}° mag" } ?: ""),
            )
        }
    }
}

/** An arrow toward the waypoint, relative to where the phone points; none without a compass. */
@Composable
private fun Arrow(deg: Double?, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (deg == null) return@Canvas
        rotate(degrees = deg.toFloat()) {
            val r = size.minDimension / 2
            drawLine(color, Offset(center.x, center.y + r * 0.8f), Offset(center.x, center.y - r * 0.8f), strokeWidth = 6f)
            drawLine(color, Offset(center.x, center.y - r * 0.8f), Offset(center.x - r * 0.4f, center.y - r * 0.3f), strokeWidth = 6f)
            drawLine(color, Offset(center.x, center.y - r * 0.8f), Offset(center.x + r * 0.4f, center.y - r * 0.3f), strokeWidth = 6f)
        }
    }
}

@Composable
private fun SunMoonPane(state: FieldUiState, actions: FieldActions, zone: ZoneId) {
    DeckPane(header = "Sun and moon") {
        val s = state.sun
        if (s == null) {
            Text("Sun and moon times need a GPS fix (they depend on where you are).")
            return@DeckPane
        }
        s.polarNote?.let { Text(it) }
        Text("Sunrise ${FieldReadout.clock(s.sunriseMs, zone)}   Sunset ${FieldReadout.clock(s.sunsetMs, zone)}")
        Text("Civil dawn ${FieldReadout.clock(s.civilDawnMs, zone)}   Civil dusk ${FieldReadout.clock(s.civilDuskMs, zone)}")
        Text("Nautical dawn ${FieldReadout.clock(s.nauticalDawnMs, zone)}   Nautical dusk ${FieldReadout.clock(s.nauticalDuskMs, zone)}")
        state.turnaround?.let { t ->
            val line = when (t.status) {
                TurnaroundStatus.NO_SUNSET -> "No sunset today, so no turnaround time."
                TurnaroundStatus.AFTER_SUNSET -> "The sun has set."
                TurnaroundStatus.PAST_TURNAROUND -> "Turnaround was ${FieldReadout.clock(t.atMs, zone)}: past it."
                TurnaroundStatus.AHEAD -> "Turn around by ${FieldReadout.clock(t.atMs, zone)}"
            }
            Text("$line (sunset less ${t.marginMin} min)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckButton(text = "Margin -15", onClick = { actions.onMargin(t.marginMin - 15) }, modifier = Modifier.weight(1f))
                DeckButton(text = "Margin +15", onClick = { actions.onMargin(t.marginMin + 15) }, modifier = Modifier.weight(1f))
            }
        }
        state.moon?.let { m ->
            Text(
                "Moon: ${m.phaseName}, ${m.illuminationPct}% lit. Rises ${FieldReadout.clock(m.riseMs, zone)}, " +
                    "sets ${FieldReadout.clock(m.setMs, zone)}",
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
