package com.kevin.legion.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.field.CompassSensor
import com.kevin.legion.field.Declination
import com.kevin.legion.field.FieldGps
import com.kevin.legion.field.FieldReadout
import com.kevin.legion.field.FieldUiState
import com.kevin.legion.field.FieldWaypoints
import com.kevin.legion.field.FixInput
import com.kevin.legion.field.GpsState
import com.kevin.legion.field.GpsStatus
import com.kevin.legion.field.Waypoint
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Field screen's ONE state (round 5, field navigation). Sensors in, [FieldUiState] out, built by
 * [FieldReadout.build] (pure, unit-tested); the composables only draw it, so the Typeset restyle
 * swaps them and keeps this. Offline throughout: compass, GPS, grid, sun and moon are on-device;
 * only a waypoint's trip to saved places needs signal, and it waits on the phone until then.
 */
class FieldViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val compass = CompassSensor(application)
    private val gps = FieldGps(application)
    private val waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    private val margin = MutableStateFlow(FieldUiState.DEFAULT_MARGIN_MIN)
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val extra = MutableStateFlow(Extra())

    private data class Extra(val message: String? = null, val busy: Boolean = false)

    private val _state = MutableStateFlow(FieldUiState(compassSource = compass.source))
    val state: StateFlow<FieldUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(compass.reading, gps.state, waypoints, margin, clock) { c, g, w, m, now ->
                val fix = (g as? GpsState.Fix)?.location?.let {
                    FixInput(
                        lat = it.latitude,
                        lon = it.longitude,
                        altitudeM = if (it.hasAltitude()) it.altitude else null,
                        accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                        timeMs = it.time,
                    )
                }
                FieldReadout.build(
                    c, compass.source, statusOf(g), fix, w, now, ZoneId.systemDefault(), m, Declination::at,
                )
            }.combine(extra) { s, e -> s.copy(message = e.message, markBusy = e.busy) }
                .collect { _state.value = it }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                clock.value = System.currentTimeMillis()
            }
        }
        refreshWaypoints(drain = true)
    }

    /** Sensors run only while the screen is in front (battery): call from ON_START / ON_STOP. */
    fun start() {
        compass.start()
        gps.start()
        refreshWaypoints(drain = true)
    }

    fun stop() {
        compass.stop()
        gps.stop()
    }

    /** After the location permission prompt answers. */
    fun permissionChanged() {
        gps.stop()
        gps.start()
    }

    fun setMargin(minutes: Int) {
        margin.value = minutes.coerceIn(MIN_MARGIN, MAX_MARGIN)
    }

    /** Marks [label] at the current fix, local first ([FieldWaypoints.mark]). */
    fun mark(label: String) {
        val fix = (gps.state.value as? GpsState.Fix)?.location
        if (fix == null) {
            extra.update { it.copy(message = "No GPS fix yet, so nothing was marked. Wait for a fix and try again.") }
            return
        }
        extra.update { it.copy(busy = true) }
        viewModelScope.launch {
            val outcome = FieldWaypoints.mark(app, label, fix.latitude, fix.longitude)
            extra.value = Extra(message = outcome.message, busy = false)
            refreshWaypoints(drain = false)
        }
    }

    private fun refreshWaypoints(drain: Boolean) {
        viewModelScope.launch {
            if (drain && FieldWaypoints.isOnline(app)) runCatching { FieldWaypoints.drain(app) }
            waypoints.value = runCatching { FieldWaypoints.all(app) }.getOrDefault(emptyList())
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun statusOf(g: GpsState): GpsStatus = when (g) {
        GpsState.NoPermission -> GpsStatus.NO_PERMISSION
        GpsState.Off -> GpsStatus.OFF
        GpsState.Searching -> GpsStatus.SEARCHING
        is GpsState.Fix -> GpsStatus.FIX
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_MARGIN = 0
        const val MAX_MARGIN = 240
    }
}

/** What the screen can ask for. One holder so the restyle keeps the same wiring. */
data class FieldActions(
    val onBack: () -> Unit,
    val onGrantLocation: () -> Unit,
    val onMark: (String) -> Unit,
    val onMargin: (Int) -> Unit,
)
