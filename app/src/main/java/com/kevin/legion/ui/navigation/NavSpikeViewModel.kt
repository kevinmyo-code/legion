package com.kevin.legion.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.BuildConfig
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
    private val controller = MapboxNavController(app) { BuildConfig.MAPBOX_ACCESS_TOKEN }

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

    /** Called from the screen's `onDispose`; the ViewModel can outlive composition across a back-stack entry. */
    fun onScreenLeft() = controller.onScreenLeft()

    override fun onCleared() {
        controller.onScreenLeft()
    }
}
