@file:Suppress("MagicNumber") // layout sizes and the dial's proportions

package com.kevin.legion.ui.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.field.CompassSource
import com.kevin.legion.field.FieldUiState
import com.kevin.legion.field.FieldVoice
import com.kevin.legion.field.FieldWords
import com.kevin.legion.field.GpsStatus
import com.kevin.legion.field.HeadingUi
import com.kevin.legion.field.TurnaroundStatus
import com.kevin.legion.field.TurnaroundUi
import com.kevin.legion.field.WaypointUi
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.typeset.TypesetCompass
import java.time.ZoneId

/**
 * The Field screen (round 5): compass, position, waypoints, sun and moon. The compass is the Typeset
 * rose ([TypesetCompass], ADR 0081) on the real sensors; the rest is plain and built from existing
 * components, and the Typeset restyle replaces these composables while keeping [FieldUiState] and
 * [FieldViewModel].
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
        // The Typeset rose (ADR 0081) on the real sensors: the heading shown is true when a fix gives
        // a declination, else magnetic, and the nearest waypoint's bearing is given in the same frame
        // (as FieldReadout's arrow is), so the arrow is drawn at bearing less heading.
        val nearest = state.waypoints.firstOrNull()
        val bearing = nearest?.let { w ->
            if (h?.trueDeg != null) w.bearingTrueDeg else w.bearingMagDeg ?: w.bearingTrueDeg
        }
        TypesetCompass(
            heading = (h?.trueDeg ?: h?.magneticDeg)?.toFloat(),
            bearing = bearing?.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp),
        )
        nearest?.let { Text("Arrow: ${it.label}, ${it.distanceText}", style = MaterialTheme.typography.bodySmall) }
        if (h == null) {
            val waiting = if (state.compassSource == CompassSource.NONE) {
                "This phone has no compass sensor."
            } else {
                "Waiting for the compass..."
            }
            Text(waiting)
        } else {
            HeadingLines(h)
        }
        state.declinationText?.let { Text("Declination $it") }
        Text("Accuracy: ${state.compassAccuracy.words}")
        if (state.needsCalibration) {
            Text(
                "Calibrate: move the phone slowly in a figure 8, turning it over a few times, away from " +
                    "metal and the car.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "calibrate compass" },
            )
        }
    }
}

@Composable
private fun HeadingLines(h: HeadingUi) {
    val shown = h.trueDeg ?: h.magneticDeg
    Text("${FieldWords.degText(shown)}° ${h.point}", style = MaterialTheme.typography.headlineMedium)
    val trueText = h.trueDeg?.let { "  True ${FieldWords.degText(it)}°" } ?: "  (true needs a GPS fix)"
    Text("Magnetic ${FieldWords.degText(h.magneticDeg)}°$trueText")
    val back = h.backAzimuthTrueDeg
    Text(
        if (back != null) {
            "Back azimuth ${FieldWords.degText(back)}° true"
        } else {
            "Back azimuth ${FieldWords.degText(h.backAzimuthMagDeg)}° magnetic"
        },
    )
    h.northArrowDeg?.let { Text("True north: ${FieldVoice.northWords(it)}") }
    Text(if (h.upright) "Held upright: sighting along the back" else "Held flat: the top edge points")
}

@Composable
private fun PositionPane(state: FieldUiState, actions: FieldActions) {
    DeckPane(header = "Position (GPS only)") {
        when (state.gps) {
            GpsStatus.NO_PERMISSION -> {
                Text("LEGION has no location permission, so there is no GPS position.")
                DeckButton(
                    text = "Allow location",
                    onClick = actions.onGrantLocation,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            Text("Fix ${FieldVoice.ageText(p.fixAgeMs)}")
        }
    }
}

@Composable
private fun WaypointsPane(state: FieldUiState, actions: FieldActions) {
    var name by remember { mutableStateOf("") }
    val canMark = !state.markBusy && state.position != null
    DeckPane(header = "Waypoints") {
        DeckButton(
            text = "Mark car here",
            onClick = { actions.onMark("car") },
            enabled = canMark,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name a spot") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        DeckButton(
            text = "Mark this spot",
            onClick = {
                actions.onMark(name)
                name = ""
            },
            enabled = canMark && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
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
            val pending = if (w.pending) " (on this phone, not synced yet)" else ""
            Text(w.label + pending, style = MaterialTheme.typography.titleSmall)
            val magnetic = w.bearingMagDeg?.let { " / ${FieldWords.degText(it)}° mag" } ?: ""
            Text(
                "${w.distanceText} straight line, bearing ${FieldWords.degText(w.bearingTrueDeg)}° true" +
                    magnetic,
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
            val tip = Offset(center.x, center.y - r * 0.8f)
            drawLine(color, Offset(center.x, center.y + r * 0.8f), tip, strokeWidth = 6f)
            drawLine(color, tip, Offset(center.x - r * 0.4f, center.y - r * 0.3f), strokeWidth = 6f)
            drawLine(color, tip, Offset(center.x + r * 0.4f, center.y - r * 0.3f), strokeWidth = 6f)
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
        Text("Sunrise ${FieldVoice.clock(s.sunriseMs, zone)}   Sunset ${FieldVoice.clock(s.sunsetMs, zone)}")
        Text(
            "Civil dawn ${FieldVoice.clock(s.civilDawnMs, zone)}   " +
                "Civil dusk ${FieldVoice.clock(s.civilDuskMs, zone)}",
        )
        Text(
            "Nautical dawn ${FieldVoice.clock(s.nauticalDawnMs, zone)}   " +
                "Nautical dusk ${FieldVoice.clock(s.nauticalDuskMs, zone)}",
        )
        state.turnaround?.let { TurnaroundBlock(it, actions, zone) }
        state.moon?.let { m ->
            Text(
                "Moon: ${m.phaseName}, ${m.illuminationPct}% lit. Rises ${FieldVoice.clock(m.riseMs, zone)}, " +
                    "sets ${FieldVoice.clock(m.setMs, zone)}",
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TurnaroundBlock(t: TurnaroundUi, actions: FieldActions, zone: ZoneId) {
    val line = when (t.status) {
        TurnaroundStatus.NO_SUNSET -> "No sunset today, so no turnaround time."
        TurnaroundStatus.AFTER_SUNSET -> "The sun has set."
        TurnaroundStatus.PAST_TURNAROUND -> "Turnaround was ${FieldVoice.clock(t.atMs, zone)}: past it."
        TurnaroundStatus.AHEAD -> "Turn around by ${FieldVoice.clock(t.atMs, zone)}"
    }
    Text(
        "$line (sunset less ${t.marginMin} min)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckButton(
            text = "Margin -15",
            onClick = { actions.onMargin(t.marginMin - 15) },
            modifier = Modifier.weight(1f),
        )
        DeckButton(
            text = "Margin +15",
            onClick = { actions.onMargin(t.marginMin + 15) },
            modifier = Modifier.weight(1f),
        )
    }
}
