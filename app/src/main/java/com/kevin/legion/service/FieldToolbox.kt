package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.field.CompassSensor
import com.kevin.legion.field.Declination
import com.kevin.legion.field.FieldReadout
import com.kevin.legion.field.FieldUiState
import com.kevin.legion.field.FieldVoice
import com.kevin.legion.field.FieldWaypoints
import com.kevin.legion.field.FixInput
import com.kevin.legion.field.GpsStatus
import com.kevin.legion.location.LocationController
import java.time.ZoneId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Field navigation's voice path (round 5): no new declaration. `get_current_location`'s RESULT
 * carries the field readout (billed per call, not per Live setup; LiveSetupPayloadSizeTest), so
 * "which way is north", "bearing to the car" and "when's sunset" are answered from the same
 * [FieldReadout] and [FieldVoice] the Field screen uses (ADR 0035: one controller, two doors).
 *
 * The compass is read once for up to [COMPASS_WAIT_MS] and switched off again; a phone with no
 * compass, or one that answers too slowly, gets "no reading" in words, never a guessed heading.
 */
object FieldToolbox {
    const val COMPASS_WAIT_MS = 1_500L

    /** The spoken field readout for the fix [LocationController] holds (null: none yet). */
    suspend fun readout(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val app = context.applicationContext
        val location = LocationController.state.value
        val fix = location?.let {
            FixInput(
                lat = it.latitude,
                lon = it.longitude,
                altitudeM = if (it.hasAltitude()) it.altitude else null,
                accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                timeMs = it.time,
            )
        }
        val compass = CompassSensor(app)
        val reading = try {
            compass.start()
            withTimeoutOrNull(COMPASS_WAIT_MS) { compass.reading.filterNotNull().first() }
        } finally {
            compass.stop()
        }
        // Voice means a connection: send any waiting field marks first, so "the car" is current.
        if (FieldWaypoints.isOnline(app)) runCatching { FieldWaypoints.drain(app) }
        val waypoints = runCatching { FieldWaypoints.all(app) }.getOrDefault(emptyList())
        val state = FieldReadout.build(
            compass = reading,
            compassSource = compass.source,
            gps = if (fix != null) GpsStatus.FIX else GpsStatus.SEARCHING,
            fix = fix,
            waypoints = waypoints,
            nowMs = nowMs,
            zone = ZoneId.systemDefault(),
            marginMin = FieldUiState.DEFAULT_MARGIN_MIN,
            declination = Declination::at,
        )
        return FieldVoice.voiceText(state, ZoneId.systemDefault())
    }
}
