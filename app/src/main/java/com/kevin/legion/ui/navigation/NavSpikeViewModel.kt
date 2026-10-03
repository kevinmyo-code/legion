package com.kevin.legion.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.MidnightApplication
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.NavDestinations
import com.kevin.legion.navigation.NavState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The mapbox-nav spike screen's ViewModel (CLAUDE.md sec 8: one `StateFlow<UiState>` per screen;
 * here [state] is the controller's own [NavState], which already is the screen's whole state).
 * `AndroidViewModel` because Hilt is not in the tree yet - the same stopgap as `HomeViewModel`.
 *
 * The controller is owned here and torn down in [onCleared], which with the screen's own
 * `DisposableEffect` is the "no trip outlives its screen" guarantee (ticket 07).
 */
class NavSpikeViewModel(app: Application) : AndroidViewModel(app) {
    private val tokens = (app as MidnightApplication).mapboxTokens
    private val controller = MapboxNavController(app, tokens)

    init {
        // A paste, a clear or a rejection while this screen is alive re-reads the state from the
        // token (and ends a trip started on the old one). The first emission is a harmless no-op.
        viewModelScope.launch { tokens.state.collect { controller.onTokenChanged() } }
    }

    val state: StateFlow<NavState> = controller.state
    val routes = controller.routes

    /** The spike's "Route to test destination" button. */
    fun routeToTestDestination() {
        viewModelScope.launch {
            val places = runCatching { PlaceController.all(getApplication()) }.getOrDefault(emptyList())
            val dest = NavDestinations.forSpike(places)
            controller.startGuidance(LocationController.state.value, dest.latitude, dest.longitude, dest.name)
        }
    }

    fun stop() = controller.stop()

    /** The map's style load failed with an auth error: Mapbox refused the token. */
    fun onMapAuthFailure() = controller.onTokenRefused()

    /** Called from the screen's `onDispose`; the ViewModel can outlive composition across a back-stack entry. */
    fun onScreenLeft() = controller.onScreenLeft()

    override fun onCleared() {
        controller.onScreenLeft()
    }
}
