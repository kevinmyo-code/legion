package com.kevin.legion.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.MidnightApplication
import com.kevin.legion.location.LocationController
import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavCameraMode
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavResult
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavState
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.LookupContext
import com.kevin.legion.navigation.resolve.PhonePlacesReader
import com.kevin.legion.navigation.resolve.PlacesRead
import com.kevin.legion.navigation.resolve.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the person is being asked to confirm: several plausible places, top pick first. */
data class ChoiceUi(val forStop: Boolean, val query: String, val candidates: List<Candidate>, val notes: List<String>)

/** Which small panel is open over the guiding sheet. */
enum class NavPanel { NONE, STOPS, ROUTES }

/**
 * Everything the screen draws: the controller's own [NavState] plus what only the screen knows
 * (the typed destination, a lookup in progress, an ambiguity waiting for a yes, a sentence about
 * what did not work). One flow, as CLAUDE.md sec 8 asks of a ViewModel.
 */
data class NavUiState(
    val nav: NavState,
    val input: String = "",
    val resolving: Boolean = false,
    val choice: ChoiceUi? = null,
    /** The last refusal or failure, in words (a miss from the resolver, or a controller result that was not ok). */
    val problem: String? = null,
    val savedPlaceLabels: List<String> = emptyList(),
    val panel: NavPanel = NavPanel.NONE,
    val stopInput: String = "",
)

private data class LocalState(
    val input: String = "",
    val resolving: Boolean = false,
    val choice: ChoiceUi? = null,
    val problem: String? = null,
    val labels: List<String> = emptyList(),
    val panel: NavPanel = NavPanel.NONE,
    val stopInput: String = "",
)

/**
 * The nav screen's ViewModel (CLAUDE.md sec 8: one `StateFlow<UiState>`). `AndroidViewModel` because
 * Hilt is not in the tree yet, the same stopgap `HomeViewModel` documents.
 *
 * **It owns no trip.** The controller is app-owned ([MidnightApplication.navController]) and
 * outlives this ViewModel (ticket 07); clearing the ViewModel never ends a guided trip. Every
 * button here calls the same controller method the matching voice tool will call in ticket 11
 * (ADR 0035's hands path), and a destination typed here goes through the same [DestinationResolver].
 */
// One method per controller verb is the point: every button maps to the voice tool's own verb (ADR 0035).
@Suppress("TooManyFunctions")
class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as MidnightApplication
    private val controller = application.navController
    private val resolver = application.navResolver
    private val tokens = application.mapboxTokens
    private val places = PhonePlacesReader(app)

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<NavUiState> = combine(controller.state, local) { nav, l ->
        NavUiState(nav, l.input, l.resolving, l.choice, l.problem, l.labels, l.panel, l.stopInput)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NavUiState(controller.state.value))

    init {
        // A paste, a clear or a rejection while this screen is alive re-reads the state from the
        // token. The controller ignores the first emission (the token it already handled), so
        // entering the screen mid-trip never ends the trip.
        viewModelScope.launch { tokens.state.collect { controller.onTokenChanged() } }
        refreshPlaces()
    }

    private fun fix(): GeoPoint? = LocationController.state.value?.let { GeoPoint(it.latitude, it.longitude) }

    private fun refreshPlaces() {
        viewModelScope.launch {
            val read = places.read()
            local.update { it.copy(labels = (read as? PlacesRead.Places)?.places?.map { p -> p.label }.orEmpty()) }
        }
    }

    // ------------------------------------------------------------------ choosing a destination

    fun onInput(text: String) = local.update { it.copy(input = text, problem = null) }

    /** The typed field's Go. The hands path for `navigate`: the same resolver, then a preview. */
    fun submit() {
        val text = local.value.input
        if (text.isBlank() || local.value.resolving) return
        viewModelScope.launch { resolveAndPreview(text) }
    }

    /** A saved place's Navigate action, or a chip: the label goes through the resolver like any phrase. */
    fun navigateTo(label: String) {
        if (controller.state.value.phase == NavPhase.GUIDING) return
        local.update { it.copy(input = label) }
        viewModelScope.launch { resolveAndPreview(label) }
    }

    private suspend fun resolveAndPreview(text: String) {
        local.update { it.copy(resolving = true, problem = null, choice = null) }
        val resolution = resolver.resolve(text, LookupContext(fix()))
        when (resolution) {
            is Resolution.NotFound -> local.update { it.copy(resolving = false, problem = resolution.message) }
            is Resolution.Resolved -> {
                if (resolution.ambiguous) {
                    local.update {
                        it.copy(
                            resolving = false,
                            choice = ChoiceUi(false, text, resolution.candidates, resolution.notes),
                        )
                    }
                } else {
                    local.update { it.copy(resolving = false) }
                    previewTo(resolution.destination)
                }
            }
        }
    }

    /** Yes to one of the places the screen asked about. */
    fun pickCandidate(candidate: Candidate) {
        val choice = local.value.choice ?: return
        local.update { it.copy(choice = null) }
        viewModelScope.launch {
            if (choice.forStop) addStopTo(candidate.toDestination()) else previewTo(candidate.toDestination())
        }
    }

    fun dismissChoice() = local.update { it.copy(choice = null) }

    private suspend fun previewTo(destination: NavDestination) {
        val result = controller.preview(destination)
        local.update { it.copy(problem = if (result.ok) null else result.message) }
    }

    // ------------------------------------------------------------------ preview and guiding

    fun pickRoute(index: Int) {
        viewModelScope.launch { report(controller.takeAlternative(index)) }
    }

    fun start() {
        viewModelScope.launch { report(controller.start()) }
    }

    /** Back out of a preview (nothing was started); also what End does with no trip. */
    fun cancelPreview() {
        controller.end()
        local.update { it.copy(problem = null, choice = null, panel = NavPanel.NONE) }
    }

    fun end() {
        report(controller.end())
        local.update { it.copy(panel = NavPanel.NONE) }
    }

    fun dismiss() {
        controller.dismiss()
        local.update { it.copy(input = "", problem = null) }
    }

    fun toggleTolls() {
        viewModelScope.launch { report(controller.toggleAvoid(AvoidKind.TOLLS)) }
    }

    fun toggleMute() {
        report(controller.setMuted(!controller.state.value.muted))
    }

    fun overviewOrRecenter() {
        val camera = controller.state.value.camera
        report(if (camera == NavCameraMode.FOLLOWING) controller.overview() else controller.recenter())
    }

    fun openPanel(panel: NavPanel) = local.update { it.copy(panel = panel, problem = null) }

    fun closePanel() = local.update { it.copy(panel = NavPanel.NONE, stopInput = "") }

    fun onStopInput(text: String) = local.update { it.copy(stopInput = text, problem = null) }

    /** Add a stop typed in the stop panel: searched ALONG the route (ticket 03's `via`). */
    fun addStop() {
        val text = local.value.stopInput
        if (text.isBlank() || local.value.resolving) return
        viewModelScope.launch {
            local.update { it.copy(resolving = true, problem = null) }
            val ctx = LookupContext(fix(), controller.primaryGeometry().takeIf { it.isNotEmpty() })
            when (val resolution = resolver.resolve(text, ctx)) {
                is Resolution.NotFound -> local.update { it.copy(resolving = false, problem = resolution.message) }
                is Resolution.Resolved -> {
                    local.update { it.copy(resolving = false) }
                    if (resolution.ambiguous) {
                        local.update {
                            it.copy(choice = ChoiceUi(true, text, resolution.candidates, resolution.notes))
                        }
                    } else {
                        addStopTo(resolution.destination)
                    }
                }
            }
        }
    }

    private suspend fun addStopTo(stop: NavDestination) {
        report(controller.addStop(stop))
        local.update { it.copy(stopInput = "") }
    }

    fun dropStop(name: String) {
        viewModelScope.launch { report(controller.removeStop(name)) }
    }

    private fun report(result: NavResult) {
        local.update { it.copy(problem = if (result.ok) null else result.message) }
    }

    // ------------------------------------------------------------------ map callbacks

    /** The map's style load failed with an auth error: Mapbox refused the token. */
    fun onMapAuthFailure() = controller.onTokenRefused()

    fun onCameraDetached() = controller.cameraDetached()

    /** Screen entered: refresh what the idle screen lists, and take the camera back onto a trip already running. */
    fun onScreenEntered() {
        refreshPlaces()
        if (controller.state.value.phase == NavPhase.GUIDING) controller.recenter()
    }

    /**
     * The back-stack entry is gone (not a rotation: this ViewModel survives that). **A guiding trip is
     * untouched** (ticket 07, ticket 10); a preview or an unfinished request is dropped, since neither
     * is a trip, and a finished trip's verdict is cleared.
     */
    override fun onCleared() {
        controller.onScreenLeft()
    }
}
