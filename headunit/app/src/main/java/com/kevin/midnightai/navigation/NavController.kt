package com.kevin.midnightai.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The head unit's trip rules (HU-1 T5), against the [NavSdk] seam so they run with a fake. Every
 * call and callback is on the main thread.
 *
 * - No map token (the phone has not handed one over): NO_TOKEN, "Pair your phone to get maps".
 * - The unit cannot run the map ([availability] gives the reason in words) or the SDK fails:
 *   UNAVAILABLE, and the CAR face stays. Never a crash of the launcher.
 * - A trip: REQUESTING -> PREVIEW (one route, GO or CANCEL) -> GUIDING -> ARRIVED or End. A trip
 *   session runs only with a route (no Free Drive), and an outcome is read back from the SDK
 *   ([NavSdk.isSessionRunning]) before it is reported.
 * - GUIDING flips the middle panel to NAV ([onTripStarted]); End, a failure, or 30 s after arrival
 *   flips it back to CAR ([onTripEnded]).
 */
class NavController(
    /** The SDK, created lazily on first use; throws if the unit cannot run it. */
    private val sdkFactory: () -> NavSdk,
    /** True once the phone handed over a Mapbox public token. */
    private val hasToken: () -> Boolean,
    /** Null when the unit can run the map, else why not, in words. */
    private val availability: () -> String?,
    /** The car's position now (the phone-fed fix), or null. */
    private val position: () -> GeoPoint?,
    private val onTripStarted: () -> Unit,
    private val onTripEnded: () -> Unit,
    /** Runs [block] after [delayMs] on the main thread (the arrival hold). */
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    private val _state = MutableStateFlow(NavState(NavPhase.IDLE, IDLE_WORDS))
    val state: StateFlow<NavState> = _state.asStateFlow()

    private var sdk: NavSdk? = null
    private var pendingToken: Any? = null
    private var tripEpoch = 0

    /** Re-reads token and capability when no trip is in progress (the NAV face calls this on open). */
    fun refresh() {
        val phase = _state.value.phase
        if (phase == NavPhase.GUIDING || phase == NavPhase.REQUESTING || phase == NavPhase.PREVIEW) return
        _state.value = baseState()
    }

    private fun baseState(): NavState = when {
        !hasToken() -> NavState(NavPhase.NO_TOKEN, NO_TOKEN_WORDS)
        availability() != null -> NavState(NavPhase.UNAVAILABLE, unavailableWords(availability()!!))
        else -> NavState(NavPhase.IDLE, IDLE_WORDS)
    }

    /** True when the map may be drawn now. */
    fun mapAllowed(): Boolean = hasToken() && availability() == null && _state.value.phase != NavPhase.UNAVAILABLE

    private fun sdkOrNull(): NavSdk? {
        sdk?.let { return it }
        return try {
            sdkFactory().also { s ->
                s.listener = listener
                sdk = s
            }
        } catch (e: Throwable) {
            failUnavailable(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Asks for a route to [destination]; [onResult] says what happened, read back from state. */
    fun navigateTo(destination: NavDestination, onResult: (NavResult) -> Unit = {}) {
        val base = baseState()
        if (base.phase != NavPhase.IDLE) {
            _state.value = base
            return onResult(NavResult(false, base.message))
        }
        if (_state.value.phase == NavPhase.GUIDING) {
            return onResult(NavResult(false, "A trip is already running. End it first, then start the new one."))
        }
        val origin = position() ?: run {
            val words = "No position from the phone yet, so no route was asked for. Check the car link, then try again."
            _state.value = NavState(NavPhase.FAILED, words, destination)
            return onResult(NavResult(false, words))
        }
        val s = sdkOrNull() ?: return onResult(NavResult(false, _state.value.message))
        val epoch = ++tripEpoch
        _state.value = NavState(NavPhase.REQUESTING, "Finding a route to ${destination.name}...", destination)
        try {
            s.requestRoute(origin, destination.point) { result ->
                if (epoch != tripEpoch) return@requestRoute // superseded or cancelled
                when (result) {
                    is RouteResult.Ready -> {
                        pendingToken = result.token
                        _state.value = NavState(
                            NavPhase.PREVIEW,
                            "Route to ${destination.name}: ${NavFormat.duration(result.route.durationS)}, ${NavFormat.distance(result.route.distanceM)}.",
                            destination,
                            result.route,
                        )
                        onResult(NavResult(true, _state.value.message + " Say go or tap GO."))
                    }
                    is RouteResult.Failed -> {
                        val words = "No route to ${destination.name}: ${result.message}"
                        _state.value = NavState(NavPhase.FAILED, words, destination)
                        onResult(NavResult(false, words))
                    }
                }
            }
        } catch (e: Throwable) {
            failUnavailable(e.message ?: e.javaClass.simpleName)
            onResult(NavResult(false, _state.value.message))
        }
    }

    /** GO on the preview: sets the route, starts guidance, and reports it only if the session runs. */
    fun go(onResult: (NavResult) -> Unit = {}) {
        val current = _state.value
        val token = pendingToken
        val s = sdk
        if (current.phase != NavPhase.PREVIEW || token == null || s == null) {
            return onResult(NavResult(false, "There is no route to start. Pick a place first."))
        }
        val epoch = tripEpoch
        try {
            s.setRoute(token) { taken ->
                if (epoch != tripEpoch) return@setRoute
                if (!taken) {
                    val words = "The map did not take the route, so guidance did not start."
                    _state.value = current.copy(phase = NavPhase.FAILED, message = words)
                    return@setRoute onResult(NavResult(false, words))
                }
                val started = runCatching { s.startSession() }.exceptionOrNull()
                if (started != null || !s.isSessionRunning()) {
                    runCatching { s.stopAndClear() }
                    val words = "Guidance did not start" +
                        (started?.message?.let { ": $it." } ?: ".") + " Nothing is running."
                    _state.value = current.copy(phase = NavPhase.FAILED, message = words)
                    return@setRoute onResult(NavResult(false, words))
                }
                pendingToken = null
                _state.value = current.copy(phase = NavPhase.GUIDING, message = "Guiding to ${current.destination?.name}.", following = true)
                onTripStarted()
                onResult(NavResult(true, _state.value.message))
            }
        } catch (e: Throwable) {
            failUnavailable(e.message ?: e.javaClass.simpleName)
            onResult(NavResult(false, _state.value.message))
        }
    }

    /** CANCEL on the preview, or back out of a failed request. */
    fun cancel() {
        tripEpoch++
        pendingToken = null
        if (_state.value.phase == NavPhase.GUIDING) return
        sdk?.let { runCatching { it.stopAndClear() } }
        _state.value = baseState()
    }

    /** END: stops guidance; reports it only once the session is really stopped. */
    fun end(onResult: (NavResult) -> Unit = {}) {
        val s = sdk
        if (_state.value.phase != NavPhase.GUIDING || s == null) {
            cancel()
            return onResult(NavResult(true, "No trip is running."))
        }
        tripEpoch++
        runCatching { s.stopAndClear() }
        if (s.isSessionRunning()) {
            return onResult(NavResult(false, "The trip did not stop on the map. Try END again."))
        }
        _state.value = baseState().copy(message = "Trip ended.")
        onTripEnded()
        onResult(NavResult(true, "Trip ended."))
    }

    fun recenter() {
        _state.value = _state.value.copy(following = true)
    }

    /** The driver moved the map by hand: stop following until RECENTER. */
    fun mapMovedByHand() {
        if (_state.value.following) _state.value = _state.value.copy(following = false)
    }

    /** The map surface itself failed (style, GL, native): fall back to the car face with words. */
    fun mapFailed(reason: String) = failUnavailable(reason)

    private fun failUnavailable(reason: String) {
        val wasGuiding = _state.value.phase == NavPhase.GUIDING
        tripEpoch++
        pendingToken = null
        sdk?.let { runCatching { it.stopAndClear() } }
        _state.value = NavState(NavPhase.UNAVAILABLE, unavailableWords(reason))
        if (wasGuiding) onTripEnded()
    }

    private val listener = object : NavSdkListener {
        override fun onProgress(progress: NavProgress) {
            val st = _state.value
            if (st.phase == NavPhase.GUIDING) _state.value = st.copy(progress = progress)
        }

        override fun onRerouting(active: Boolean) {
            val st = _state.value
            if (st.phase == NavPhase.GUIDING) _state.value = st.copy(rerouting = active)
        }

        override fun onArrival() {
            val st = _state.value
            if (st.phase != NavPhase.GUIDING) return
            val epoch = ++tripEpoch
            sdk?.let { runCatching { it.stopAndClear() } }
            _state.value = st.copy(phase = NavPhase.ARRIVED, message = "Arrived at ${st.destination?.name}.", rerouting = false)
            schedule(ARRIVAL_HOLD_MS) {
                if (epoch == tripEpoch && _state.value.phase == NavPhase.ARRIVED) {
                    _state.value = baseState()
                    onTripEnded()
                }
            }
        }
    }

    companion object {
        const val ARRIVAL_HOLD_MS = 30_000L
        const val NO_TOKEN_WORDS = "Pair your phone to get maps: the map token comes from it over the car link."
        const val IDLE_WORDS = "No trip. Pick a saved place, or say \"navigate to\" and its name."
        fun unavailableWords(reason: String) =
            "Maps are not available on this unit: ${reason.trimEnd('.')}. The car face stays; everything else works."
    }
}
