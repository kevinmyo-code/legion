package com.kevin.midnightai.ui.cruise

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Cruise's middle panel (HU-1 T1, Kevin's approved prototype 2026-11-19): CAR (speed, gauges,
 * stored codes) or NAV, behind a CAR / NAV switch. This file is the seam the navigation ticket
 * (T5) plugs into; T1 ships NAV as a placeholder only.
 *
 * Rules (pure, [MiddlePanelLogic.reduce]):
 *  - a trip starting flips the panel to NAV, a trip ending flips it back to CAR;
 *  - a tap on CAR or NAV wins until the next trip event;
 *  - a repeated "trip started" while one is already running (a reroute, a second listener) is not
 *    a new trip event and leaves the driver's pick alone.
 * Any delay before going back to CAR after arrival is T5's to time: the reducer has no clock.
 */

enum class MiddleFace { CAR, NAV }

data class MiddlePanelState(
    val face: MiddleFace = MiddleFace.CAR,
    val tripActive: Boolean = false,
    /** True after a tap on CAR/NAV, until the next trip event. */
    val manual: Boolean = false,
)

sealed interface MiddleEvent {
    data object TripStarted : MiddleEvent
    data object TripEnded : MiddleEvent
    data class Selected(val face: MiddleFace) : MiddleEvent
}

object MiddlePanelLogic {
    fun reduce(state: MiddlePanelState, event: MiddleEvent): MiddlePanelState = when (event) {
        MiddleEvent.TripStarted ->
            if (state.tripActive) state else MiddlePanelState(face = MiddleFace.NAV, tripActive = true, manual = false)
        MiddleEvent.TripEnded ->
            if (!state.tripActive) state else MiddlePanelState(face = MiddleFace.CAR, tripActive = false, manual = false)
        is MiddleEvent.Selected -> state.copy(face = event.face, manual = true)
    }
}

/**
 * What the NAV face draws. T1 ships [NoTripNavHost]; T5 installs the real one with
 * [MiddlePanel.install]. Its content must follow the head unit's motion rule for anything it draws
 * itself (the SDK's own map surface is the approved exception, headunit/CLAUDE.md §12).
 */
interface NavPanelHost {
    @Composable
    fun Content(modifier: Modifier)
}

/**
 * Process-wide holder, so the face survives Cruise leaving composition (Settings, the app tray)
 * and a trip event that lands while Cruise is not showing still counts. Same shape as
 * [com.kevin.midnightai.service.GlanceCardController]: plain state, no timer, no scope.
 */
object MiddlePanel {
    private val _state = MutableStateFlow(MiddlePanelState())
    val state: StateFlow<MiddlePanelState> = _state.asStateFlow()

    private val _host = MutableStateFlow<NavPanelHost>(NoTripNavHost)

    /** What the NAV face draws; a flow so a host installed after Cruise composed still shows. */
    val host: StateFlow<NavPanelHost> = _host.asStateFlow()

    /** T5: the navigation surface. */
    fun install(host: NavPanelHost) {
        _host.value = host
    }

    /** T5: a trip with a route started. */
    fun tripStarted() = dispatch(MiddleEvent.TripStarted)

    /** T5: the trip ended (arrival, End, cancelled). */
    fun tripEnded() = dispatch(MiddleEvent.TripEnded)

    /** The CAR / NAV keys. */
    fun select(face: MiddleFace) = dispatch(MiddleEvent.Selected(face))

    private fun dispatch(event: MiddleEvent) = _state.update { MiddlePanelLogic.reduce(it, event) }

    /** Tests only: back to a fresh start. */
    internal fun resetForTest() {
        _state.value = MiddlePanelState()
        _host.value = NoTripNavHost
    }
}
