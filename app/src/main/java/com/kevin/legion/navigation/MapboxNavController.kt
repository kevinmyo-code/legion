package com.kevin.legion.navigation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The one navigation controller (`.scratch/mapbox-nav/`, ADR 0054, ticket 10). A plain class with
 * constructor injection and no `object` (CLAUDE.md sec 8); **there is no Hilt in the tree yet**, so
 * the Application builds the single instance (like `MidnightApplication.mapboxTokens`) and the
 * ViewModel and, in ticket 11, the voice tools both call it. Adding `@Inject constructor` later
 * changes nothing in here.
 *
 * **It outlives the screen (ticket 07).** A trip ends only on arrival, [end], or process death;
 * [onScreenLeft] never ends a GUIDING trip. The SDK's own foreground service carries guidance with
 * the screen off.
 *
 * **Its verbs are the four voice tools' verbs (ticket 04)**, so ticket 11 only adds wrappers:
 * [preview] / [navigate] / [start], [addStop] / [removeStop] / [setAvoid] / [takeAlternative] /
 * [setMuted] / [overview] / [recenter], [status], [end]. Every mutating call returns a [NavResult]
 * whose `ok` is read from the SDK AFTER the call ([NavSdk.activeRoutes], [NavSdk.isSessionRunning]),
 * never from the call having been made; a failed change says in words that the trip is unchanged.
 * [status] with no trip is [TripStatus.NotNavigating], never zeros, and an unknown value is null.
 *
 * **Billing (ticket 07), enforced by [NavTripGuard]:** a Mapbox trip is billed from
 * `startTripSession()`, so the SDK instance is created only when a route is requested, the session
 * starts only in [start], the session is stopped BEFORE routes are cleared (clearing under a live
 * session starts a Free Drive trip), and every way out ([end], arrival, a token change, a failed
 * start) tears the instance down. A route request with no session running is billed per request by
 * Mapbox; that one cost outside a trip is unavoidable and well inside the free tier.
 *
 * **Nothing from Mapbox is stored** (ToS 2.7.2 / 2.10.1): routes and destinations live in this
 * object's memory and die with the process.
 *
 * Main-thread only: every Mapbox callback and every call here is on the main looper. A route
 * response that arrives after [end] or a newer request is refused by the [epoch] check and changes
 * nothing.
 */
@Suppress("TooManyFunctions") // one controller owns the whole trip; splitting it would split the guard's invariant
class MapboxNavController(
    private val tokens: MapboxTokenSource,
    private val sdkFactory: () -> NavSdk,
    private val fix: () -> GeoPoint?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : NavSdkListener {
    private val guard = NavTripGuard()
    private var sdk: NavSdk? = null
    private var requestId: Long? = null
    private var inFlight: CompletableDeferred<RouteRequestResult>? = null

    /** The routes a preview came back with, in the SDK's own objects; consumed by [start]. */
    private var pendingToken: Any? = null

    /** Bumped by every event that invalidates an in-flight request (end, newer request, token, screen left). */
    private var epoch = 0L

    /** One change at a time: a second add-stop while the first is rebuilding is refused, not queued. */
    private var busy = false

    private var handledToken: MapboxTokenState = tokens.state.value

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private fun initialState(muted: Boolean = false): NavState =
        NavFormat.stateForToken(tokens.state.value)?.copy(muted = muted)
            ?: NavState(NavPhase.IDLE, "Ready. ${NavFormat.NOTHING_NAVIGATING}", muted = muted)

    private fun fresh(phase: NavPhase, message: String) =
        NavState(phase, message, muted = _state.value.muted, avoid = _state.value.avoid)

    // ------------------------------------------------------------------ token

    /**
     * The token changed under us (pasted, cleared, or rejected). A trip on the old token ends, since
     * it was started on a token that is gone or no longer trusted. **A no-op when the token is the
     * one already handled**, because every screen entry re-collects the token flow and its first
     * emission must never end a running trip.
     */
    fun onTokenChanged() {
        val now = tokens.state.value
        if (now == handledToken) return
        handledToken = now
        epoch++
        guard.tokenChanged()
        teardown()
        _state.value = initialState(_state.value.muted)
    }

    /** An auth failure came from somewhere other than a route request (the map's style load). */
    fun onTokenRefused() {
        epoch++
        guard.tokenChanged()
        teardown()
        tokens.markRejected()
        handledToken = tokens.state.value
        _state.value = NavState(NavPhase.TOKEN_REFUSED, NavFormat.TOKEN_REFUSED, muted = _state.value.muted)
    }

    private fun tokenRefusal(): NavResult? {
        val forced = NavFormat.stateForToken(tokens.state.value) ?: return null
        if (guard.phase != NavPhase.GUIDING) _state.value = forced.copy(muted = _state.value.muted)
        return NavResult(false, "${forced.message} ${NavFormat.NOTHING_NAVIGATING}")
    }

    // ------------------------------------------------------------------ preview and start

    /**
     * Asks for routes to [destination] (through [via] if given) and shows them without starting
     * anything. **Success means routes came back and a preview is showing; nothing is navigating.**
     * Refused while a trip is guiding: end it first.
     */
    // Each early return is one refusal with its own sentence; flattening them would hide which one fired.
    @Suppress("ReturnCount")
    suspend fun preview(
        destination: NavDestination,
        via: NavDestination? = null,
        avoid: Set<AvoidKind> = emptySet(),
    ): NavResult {
        tokenRefusal()?.let { return it }
        if (guard.phase == NavPhase.GUIDING) {
            return NavResult(false, "Already navigating to ${_state.value.destination?.name}. End that trip first.")
        }
        if (!guard.requestStarted()) return NavResult(false, "Still looking up a route. Nothing has changed.")
        val origin = fix()
        if (origin == null) {
            guard.requestFailed()
            val msg = "No location fix yet, so no route was requested. Wait for a fix and try again."
            _state.value = fresh(NavPhase.FAILED, msg)
            return NavResult(false, "$msg ${NavFormat.NOTHING_NAVIGATING}")
        }
        return runPreview(RouteRequest(origin, listOfNotNull(via), destination, avoid))
    }

    /**
     * `navigate(destination, via?, avoid?, preview?)` as one call: [preview], then [start] unless
     * [previewOnly]. Success without [previewOnly] means a route is set AND the session is running.
     */
    suspend fun navigate(
        destination: NavDestination,
        via: NavDestination? = null,
        avoid: Set<AvoidKind> = emptySet(),
        previewOnly: Boolean = false,
    ): NavResult {
        val shown = preview(destination, via, avoid)
        return if (!shown.ok || previewOnly) shown else start()
    }

    // The request, its answer and every way it can go wrong are one sequence; a Throwable from the
    // SDK is reported in words, never allowed to crash the screen.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun runPreview(req: RouteRequest): NavResult {
        val mine = ++epoch
        _state.value = fresh(NavPhase.REQUESTING, "Asking Mapbox for a route to ${req.destination.name}.").copy(
            destination = req.destination,
            stops = req.stops,
            avoid = req.avoid,
        )
        val nav = try {
            ensureSdk()
        } catch (t: Throwable) {
            return failStart(t)
        }
        val result = try {
            awaitRoutes(nav, req)
        } catch (e: CancellationException) {
            // The caller's scope died mid-request (the screen's ViewModel cleared). Nothing is a trip.
            if (mine == epoch) abandonRequest()
            throw e
        }
        if (mine != epoch) return NavResult(false, "That route request was superseded. Nothing changed.")
        return when (result) {
            is RouteRequestResult.Ready -> previewReady(req, result)
            is RouteRequestResult.Failed -> {
                if (result.kind == RouteFailure.AUTH) {
                    onTokenRefused()
                    NavResult(false, "${NavFormat.TOKEN_REFUSED} ${NavFormat.NOTHING_NAVIGATING}")
                } else {
                    failRequest(NavFormat.failureSentence(result.kind, result.message, req.destination.name))
                }
            }
            RouteRequestResult.Canceled ->
                failRequest("The route request was cancelled. ${NavFormat.NOTHING_NAVIGATING}")
        }
    }

    private fun previewReady(req: RouteRequest, ready: RouteRequestResult.Ready): NavResult {
        if (!guard.routesReady(ready.routes.size)) {
            return failRequest("Mapbox returned no route to ${req.destination.name}. ${NavFormat.NOTHING_NAVIGATING}")
        }
        pendingToken = ready.token
        sdk?.showPreview(ready.token)
        val sentence = previewSentence(req.destination, ready.routes)
        _state.value = fresh(NavPhase.PREVIEW, sentence).copy(
            destination = req.destination,
            stops = req.stops,
            routes = ready.routes.take(MAX_PREVIEW_ROUTES),
            selectedRoute = 0,
            avoid = req.avoid,
        )
        return NavResult(true, sentence)
    }

    private fun previewSentence(dest: NavDestination, routes: List<NavRouteInfo>): String {
        val shown = routes.take(MAX_PREVIEW_ROUTES)
        val labels = NavFormat.routeLabels(shown)
        val list = shown.indices.joinToString("; ") { i ->
            "${labels[i]} ${NavFormat.duration(shown[i].durationS)}, ${NavFormat.distance(shown[i].distanceM)}"
        }
        return "Routes to ${dest.name}: $list. Nothing has started yet."
    }

    private fun failRequest(message: String): NavResult {
        guard.requestFailed()
        teardown()
        _state.value = fresh(NavPhase.FAILED, message).copy(destination = _state.value.destination)
        return NavResult(false, message)
    }

    private fun failStart(t: Throwable): NavResult {
        Log.w(TAG, "SDK failed to start", t)
        val why = t.message ?: t::class.java.simpleName
        return failRequest("Navigation could not start: $why. ${NavFormat.NOTHING_NAVIGATING}")
    }

    /** Drops an unfinished request after its caller went away. Not a trip, so nothing is billed. */
    private fun abandonRequest() {
        guard.endRequested()
        teardown()
        _state.value = initialState(_state.value.muted)
    }

    /**
     * Starts guidance on the previewed route the user selected. **Success only when the SDK confirms
     * routes are set AND the trip session is running** (ticket 04). Not cancellable half-way: a
     * screen that dies between "set routes" and "start session" must not strand a set route.
     */
    suspend fun start(): NavResult = withContext(NonCancellable) { startNow() }

    private fun noRouteToStart() =
        NavResult(false, "There is no route ready to start. ${NavFormat.NOTHING_NAVIGATING}")

    // An SDK failure at the billing boundary is reported, never thrown; each early return is one refusal.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun startNow(): NavResult {
        tokenRefusal()?.let { return it }
        val s = _state.value
        if (guard.phase == NavPhase.GUIDING) {
            return NavResult(true, "Already navigating to ${s.destination?.name}.")
        }
        if (guard.phase != NavPhase.PREVIEW) return noRouteToStart()
        val nav = sdk ?: return noRouteToStart()
        val token = pendingToken ?: return noRouteToStart()
        val dest = s.destination ?: return noRouteToStart()
        guard.startRequested()
        val active = try {
            if (!awaitSet { nav.setRoutes(token, s.selectedRoute, it) }) null else {
                nav.startSession()
                nav.activeRoutes().takeIf { nav.isSessionRunning() && it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            null
        }
        if (active == null) {
            guard.startFailed()
            teardown()
            val msg = "Guidance did not start, so nothing is navigating."
            _state.value = fresh(NavPhase.FAILED, msg).copy(destination = dest)
            return NavResult(false, msg)
        }
        pendingToken = null
        val primary = active.first()
        val arrival = nowMs() + (primary.durationS * MS_PER_S).toLong()
        val sentence = "Navigating to ${dest.name}. ${NavFormat.duration(primary.durationS)}, " +
            "${NavFormat.distance(primary.distanceM)}, arriving ${NavFormat.arrivalClock(arrival)}."
        _state.value = fresh(NavPhase.GUIDING, sentence).copy(
            destination = dest,
            stops = s.stops,
            routes = active,
            avoid = s.avoid,
            guidance = GuidanceSnapshot(
                durationLeftS = primary.durationS,
                distanceLeftM = primary.distanceM,
                arrivalAtMs = arrival,
                turn = null,
                then = null,
                road = null,
                speedLimit = null,
                traffic = primary.trafficSummary,
            ),
        )
        return NavResult(true, sentence)
    }

    // ------------------------------------------------------------------ changes to a trip or preview

    /** Adds [stop] before the destination. Success only when the new route actually passes through it. */
    suspend fun addStop(stop: NavDestination): NavResult {
        val s = _state.value
        val newStops = s.stops + stop
        return change(
            newStops = newStops,
            newAvoid = s.avoid,
            verify = { r -> r.waypoints.size == newStops.size + 1 && r.waypoints.any { near(it, stop.point) } },
            done = { r -> "Added ${stop.name} as a stop. ${tail(r)}" },
        )
    }

    /**
     * Drops a stop. [match] is part of the stop's name; null means "the stop" and is only accepted
     * when there is exactly one (with several, asking which is the honest answer).
     */
    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    suspend fun removeStop(match: String? = null): NavResult {
        val s = _state.value
        if (s.stops.isEmpty()) return NavResult(false, "There are no extra stops to drop. The trip is unchanged.")
        val target = when {
            match.isNullOrBlank() && s.stops.size == 1 -> s.stops.first()
            match.isNullOrBlank() -> return NavResult(
                false,
                "There are ${s.stops.size} stops (${s.stops.joinToString { it.name }}). Which one? " +
                    "The trip is unchanged.",
            )
            else -> s.stops.firstOrNull { it.name.contains(match.trim(), ignoreCase = true) }
                ?: return NavResult(
                    false,
                    "No stop matches \"$match\" (stops: ${s.stops.joinToString { it.name }}). The trip is unchanged.",
                )
        }
        val newStops = s.stops - target
        return change(
            newStops = newStops,
            newAvoid = s.avoid,
            verify = { r -> r.waypoints.size == newStops.size + 1 && r.waypoints.none { near(it, target.point) } },
            done = { r -> "Dropped ${target.name}. ${tail(r)}" },
        )
    }

    /** Replaces the avoid set. Success only when the route now in the SDK carries exactly that exclusion. */
    suspend fun setAvoid(avoid: Set<AvoidKind>): NavResult {
        val s = _state.value
        if (avoid == s.avoid && guard.phase in CHANGEABLE) {
            return NavResult(true, "${avoidSentence(avoid)} was already the setting. Nothing changed.")
        }
        return change(
            newStops = s.stops,
            newAvoid = avoid,
            verify = { r -> r.excluded == avoid },
            done = { r -> "${avoidSentence(avoid)}. ${tail(r)}" },
        )
    }

    /** The tile's one-tap form of [setAvoid]: flips [kind] and keeps the rest. */
    suspend fun toggleAvoid(kind: AvoidKind): NavResult {
        val now = _state.value.avoid
        return setAvoid(if (kind in now) now - kind else now + kind)
    }

    private fun avoidSentence(avoid: Set<AvoidKind>) =
        if (avoid.isEmpty()) {
            "Allowing every road type again"
        } else {
            "Avoiding ${avoid.joinToString(" and ") { it.spoken }}"
        }

    private fun tail(r: NavRouteInfo): String =
        "Now ${NavFormat.duration(r.durationS)} and ${NavFormat.distance(r.distanceM)} " +
            "to ${_state.value.destination?.name}."

    private fun near(a: GeoPoint, b: GeoPoint) =
        abs(a.latitude - b.latitude) < WAYPOINT_TOLERANCE && abs(a.longitude - b.longitude) < WAYPOINT_TOLERANCE

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun change(
        newStops: List<NavDestination>,
        newAvoid: Set<AvoidKind>,
        verify: (NavRouteInfo) -> Boolean,
        done: (NavRouteInfo) -> String,
    ): NavResult {
        tokenRefusal()?.let { return it }
        val s = _state.value
        val dest = s.destination
        if (guard.phase !in CHANGEABLE || dest == null) {
            return NavResult(false, "There is no trip or route preview to change. ${NavFormat.NOTHING_NAVIGATING}")
        }
        if (busy) return NavResult(false, "Still working on the last change. Nothing else changed.")
        val guiding = guard.phase == NavPhase.GUIDING
        val kept = if (guiding) {
            "Trip unchanged, still going to ${dest.name}."
        } else {
            "Preview unchanged, still showing routes to ${dest.name}."
        }
        val origin = fix() ?: return NavResult(false, "$kept No location fix, so the route could not be rebuilt.")
        val nav = sdk ?: return NavResult(false, "$kept The navigation engine is not running.")
        if (!guiding) guard.requestStarted()
        busy = true
        val mine = ++epoch
        try {
            val result = try {
                awaitRoutes(nav, RouteRequest(origin, newStops, dest, newAvoid))
            } catch (e: CancellationException) {
                if (!guiding && mine == epoch) guard.abandonRequestToPreview()
                throw e
            }
            if (mine != epoch) return NavResult(false, "The trip changed while that was loading. Nothing was applied.")
            val ready = result as? RouteRequestResult.Ready
            if (ready == null) {
                if (!guiding) guard.abandonRequestToPreview()
                if (result is RouteRequestResult.Failed && result.kind == RouteFailure.AUTH) {
                    onTokenRefused()
                    return NavResult(false, "${NavFormat.TOKEN_REFUSED} ${NavFormat.NOTHING_NAVIGATING}")
                }
                val why = (result as? RouteRequestResult.Failed)
                    ?.let { NavFormat.failureSentence(it.kind, it.message, dest.name) }
                    ?: "The request was cancelled."
                return NavResult(false, "$kept $why")
            }
            if (ready.routes.isEmpty() || !verify(ready.routes.first())) {
                if (!guiding) guard.abandonRequestToPreview()
                return NavResult(false, "$kept Mapbox's route did not include that change.")
            }
            return if (guiding) {
                applyToTrip(nav, ready, newStops, newAvoid, kept, verify, done)
            } else {
                applyToPreview(ready, newStops, newAvoid, dest, done)
            }
        } finally {
            busy = false
        }
    }

    private fun applyToPreview(
        ready: RouteRequestResult.Ready,
        newStops: List<NavDestination>,
        newAvoid: Set<AvoidKind>,
        dest: NavDestination,
        done: (NavRouteInfo) -> String,
    ): NavResult {
        guard.routesReady(ready.routes.size)
        pendingToken = ready.token
        sdk?.showPreview(ready.token)
        val sentence = done(ready.routes.first())
        _state.value = fresh(NavPhase.PREVIEW, sentence).copy(
            destination = dest,
            stops = newStops,
            routes = ready.routes.take(MAX_PREVIEW_ROUTES),
            selectedRoute = 0,
            avoid = newAvoid,
        )
        return NavResult(true, sentence)
    }

    // Seven values are one change; an SDK throw is reported in words.
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun applyToTrip(
        nav: NavSdk,
        ready: RouteRequestResult.Ready,
        newStops: List<NavDestination>,
        newAvoid: Set<AvoidKind>,
        kept: String,
        verify: (NavRouteInfo) -> Boolean,
        done: (NavRouteInfo) -> String,
    ): NavResult {
        // The session keeps running through a rebuild: routes requested inside a session are free,
        // and stopping it here would end the trip the user is on.
        val set = try {
            awaitSet { nav.setRoutes(ready.token, 0, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "rebuild failed", t)
            false
        }
        val active = nav.activeRoutes()
        val confirmed = set && active.isNotEmpty() && nav.isSessionRunning()
        if (!confirmed || !verify(active.first())) {
            return NavResult(false, "$kept I could not confirm the new route took effect.")
        }
        val sentence = done(active.first())
        _state.value = _state.value.copy(
            message = sentence,
            stops = newStops,
            avoid = newAvoid,
            routes = active,
            selectedRoute = 0,
        )
        return NavResult(true, sentence)
    }

    /**
     * Takes the route at [index] of the shown list (0 is the primary). Preview: changes which route
     * Start will use. Guiding: switches the SDK's primary route. Success only when the SDK's primary
     * route is the one asked for.
     */
    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    suspend fun takeAlternative(index: Int): NavResult {
        val s = _state.value
        if (guard.phase !in CHANGEABLE) {
            return NavResult(false, "There is no trip or route preview. ${NavFormat.NOTHING_NAVIGATING}")
        }
        if (index !in s.routes.indices) {
            return NavResult(
                false,
                "There is no route number ${index + 1}; ${s.routes.size} are available. Nothing changed.",
            )
        }
        val label = NavFormat.routeLabels(s.routes)[index]
        val alreadyUsed = if (guard.phase == NavPhase.PREVIEW) index == s.selectedRoute else index == 0
        if (alreadyUsed) {
            return NavResult(true, "That is already the route in use ($label). Nothing changed.")
        }
        if (guard.phase == NavPhase.PREVIEW) {
            val r = s.routes[index]
            val sentence = "Using the $label route, ${NavFormat.duration(r.durationS)}. Nothing has started yet."
            _state.value = s.copy(selectedRoute = index, message = sentence)
            return NavResult(true, sentence)
        }
        val nav = sdk ?: return NavResult(false, "The trip is unchanged.")
        val wanted = s.routes[index].id
        val switched = awaitSet { nav.switchPrimary(index, it) }
        val active = nav.activeRoutes()
        if (!switched || active.firstOrNull()?.id != wanted) {
            return NavResult(false, "Trip unchanged, still going to ${s.destination?.name} by the same route.")
        }
        val sentence = "Switched to the $label route. ${tail(active.first())}"
        _state.value = s.copy(message = sentence, routes = active, selectedRoute = 0)
        return NavResult(true, sentence)
    }

    /**
     * Turn cues muted or not. **State only**: speaking cues is ticket 11 and never touches the
     * assistant's own voice.
     */
    fun setMuted(muted: Boolean): NavResult {
        _state.value = _state.value.copy(muted = muted)
        return NavResult(true, if (muted) "Turn cues are muted." else "Turn cues are on.")
    }

    /** Frames the whole route. Needs a preview or a trip. */
    fun overview(): NavResult = setCamera(NavCameraMode.OVERVIEW, "Showing the whole route.")

    /** Puts the camera back on the user. Needs a trip (a preview has no "you" to follow). */
    fun recenter(): NavResult = setCamera(NavCameraMode.FOLLOWING, "Following you again.")

    private fun setCamera(mode: NavCameraMode, sentence: String): NavResult {
        if (guard.phase !in CHANGEABLE) {
            return NavResult(false, "There is no route to show. ${NavFormat.NOTHING_NAVIGATING}")
        }
        _state.value = _state.value.copy(camera = mode)
        return NavResult(true, sentence)
    }

    /** The map reports the user dragged it away from the guided camera. UI only; never a voice verb. */
    fun cameraDetached() {
        if (guard.phase == NavPhase.GUIDING && _state.value.camera == NavCameraMode.FOLLOWING) {
            _state.value = _state.value.copy(camera = NavCameraMode.FREE)
        }
    }

    // ------------------------------------------------------------------ ending

    /**
     * Ends the trip: the session is stopped (BEFORE routes are cleared), the SDK instance destroyed.
     * With no trip it says "nothing to end"; a preview is cleared and said to be cleared, not "ended".
     * **Success on a running trip only when the SDK confirms the session stopped.**
     */
    fun end(): NavResult {
        val s = _state.value
        val before = guard.phase
        epoch++
        val wasRunning = guard.endRequested()
        if (!wasRunning) {
            teardown()
            _state.value = initialState(s.muted)
            return NavResult(
                true,
                if (before == NavPhase.PREVIEW || before == NavPhase.REQUESTING) {
                    "No trip was running; I cleared the route preview. ${NavFormat.NOTHING_NAVIGATING}"
                } else {
                    "Nothing to end: no trip was running."
                },
            )
        }
        val stopped = teardown()
        val short = s.guidance?.distanceLeftM?.takeIf { it > 0 }?.let { " ${NavFormat.distance(it)} short" }.orEmpty()
        _state.value = fresh(NavPhase.ENDED, "Trip ended$short. ${NavFormat.NOTHING_NAVIGATING}")
            .copy(destination = s.destination)
        return if (stopped) {
            NavResult(true, "Trip ended. ${NavFormat.NOTHING_NAVIGATING}")
        } else {
            NavResult(false, "I ended the trip but could not confirm the guidance session stopped.")
        }
    }

    /** "Done" on the arrived / ended / failed sheet. */
    fun dismiss() {
        guard.dismissed()
        if (guard.phase == NavPhase.IDLE && _state.value.phase in FINISHED) {
            _state.value = initialState(_state.value.muted)
        }
    }

    /**
     * The nav screen left composition. **A GUIDING trip is untouched** (ticket 07); a preview or an
     * in-flight request is dropped (neither is a trip) and a finished trip's verdict is cleared.
     */
    fun onScreenLeft() {
        val phase = guard.phase
        guard.screenLeft()
        when (phase) {
            NavPhase.REQUESTING, NavPhase.PREVIEW -> {
                epoch++
                teardown()
                _state.value = initialState(_state.value.muted)
            }
            NavPhase.ARRIVED, NavPhase.ENDED, NavPhase.FAILED -> _state.value = initialState(_state.value.muted)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ reads

    /** The read-only answer for `trip_status`. See [TripStatus]. */
    fun status(): TripStatus {
        val s = _state.value
        val dest = s.destination
        val g = s.guidance
        if (guard.phase == NavPhase.GUIDING && dest != null && g != null) return TripStatus.Navigating(dest, s.stops, g)
        val where = dest?.name
        return TripStatus.NotNavigating(
            when (s.phase) {
                NavPhase.NOT_SET_UP, NavPhase.TOKEN_REFUSED -> "Not navigating. ${s.message}"
                NavPhase.REQUESTING -> "Not navigating yet. Still looking up a route to $where."
                NavPhase.PREVIEW -> "Not navigating. A route to $where is previewed but has not been started."
                NavPhase.ARRIVED -> "Not navigating. The last trip arrived at $where."
                NavPhase.ENDED -> "Not navigating. The last trip to $where was ended."
                NavPhase.FAILED -> "Not navigating. The last route request failed."
                else -> "Not navigating."
            },
        )
    }

    /** The primary route's shape for along-route search; empty with no trip. */
    fun primaryGeometry(): List<GeoPoint> = sdk?.primaryGeometry().orEmpty()

    // ------------------------------------------------------------------ SDK callbacks

    override fun onProgress(progress: NavProgressInfo) {
        if (!guard.sessionShouldRun) return
        val s = _state.value
        val stopsLeft = (progress.remainingWaypoints - 1).coerceAtLeast(0)
        val stops = if (stopsLeft < s.stops.size) s.stops.takeLast(stopsLeft) else s.stops
        val old = s.guidance
        _state.value = s.copy(
            stops = stops,
            guidance = GuidanceSnapshot(
                durationLeftS = progress.durationLeftS,
                distanceLeftM = progress.distanceLeftM,
                arrivalAtMs = nowMs() + (progress.durationLeftS * MS_PER_S).toLong(),
                turn = progress.turn,
                then = progress.then,
                road = old?.road,
                speedLimit = old?.speedLimit,
                traffic = s.routes.firstOrNull()?.trafficSummary ?: old?.traffic,
            ),
        )
        // Backup to the arrival observer: a COMPLETE state with nothing beyond the final waypoint.
        if (progress.complete && progress.remainingWaypoints <= 1) onArrival()
    }

    override fun onLocation(road: String?, speedLimit: SpeedLimit?) {
        if (!guard.sessionShouldRun) return
        val s = _state.value
        val g = s.guidance ?: return
        if (g.road != road || g.speedLimit != speedLimit) {
            _state.value = s.copy(guidance = g.copy(road = road, speedLimit = speedLimit))
        }
    }

    override fun onRoutesChanged(routes: List<NavRouteInfo>) {
        if (!guard.sessionShouldRun || routes.isEmpty()) return
        val s = _state.value
        _state.value = s.copy(
            routes = routes,
            selectedRoute = 0,
            guidance = s.guidance?.copy(traffic = routes.first().trafficSummary),
        )
    }

    override fun onReroute(state: RerouteStatus, message: String?) {
        if (!guard.sessionShouldRun) return
        val s = _state.value
        _state.value = when (state) {
            RerouteStatus.FETCHING -> s.copy(rerouting = true, notice = null)
            RerouteStatus.IDLE -> s.copy(rerouting = false, notice = null)
            RerouteStatus.FAILED -> s.copy(
                rerouting = false,
                notice = "Could not find a new route${message?.let { ": $it" }.orEmpty()}. Keeping the last one; " +
                    "this usually means no connection.",
            )
        }
    }

    override fun onArrival() {
        val s = _state.value
        if (!guard.arrived()) return
        epoch++
        teardown()
        val sentence = "You have arrived at ${s.destination?.name}. ${NavFormat.NOTHING_NAVIGATING}"
        _state.value = fresh(NavPhase.ARRIVED, sentence).copy(destination = s.destination)
    }

    // ------------------------------------------------------------------ plumbing

    private fun ensureSdk(): NavSdk = sdk ?: sdkFactory().also {
        it.listener = this
        sdk = it
    }

    private suspend fun awaitRoutes(nav: NavSdk, req: RouteRequest): RouteRequestResult {
        val d = CompletableDeferred<RouteRequestResult>()
        inFlight = d
        val id = nav.requestRoutes(req) { d.complete(it) }
        requestId = id
        try {
            return d.await()
        } catch (e: CancellationException) {
            nav.cancelRequest(id)
            throw e
        } finally {
            if (inFlight === d) {
                inFlight = null
                requestId = null
            }
        }
    }

    private suspend fun awaitSet(call: ((Boolean) -> Unit) -> Unit): Boolean {
        val d = CompletableDeferred<Boolean>()
        call { d.complete(it) }
        return d.await()
    }

    /**
     * Stops the session FIRST (clearing routes under a live session would start Free Drive), clears,
     * destroys. Returns whether the SDK confirmed the session stopped. Safe with no instance.
     */
    // Every step is best-effort; the destroy in `finally` is what ends billing.
    @Suppress("TooGenericExceptionCaught")
    private fun teardown(): Boolean {
        val nav = sdk
        sdk = null
        pendingToken = null
        val waiting = inFlight
        inFlight = null
        waiting?.complete(RouteRequestResult.Canceled)
        if (nav == null) return true
        var stopped = false
        try {
            requestId?.let { nav.cancelRequest(it) }
            nav.listener = null
            nav.stopSession()
            stopped = !nav.isSessionRunning()
            nav.clearRoutes()
        } catch (t: Throwable) {
            Log.w(TAG, "teardown step failed; destroying anyway", t)
        } finally {
            requestId = null
            try {
                nav.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy failed", t)
            }
        }
        return stopped
    }

    private companion object {
        const val TAG = "MapboxNavController"
        val CHANGEABLE = setOf(NavPhase.PREVIEW, NavPhase.GUIDING)
        val FINISHED = setOf(NavPhase.ARRIVED, NavPhase.ENDED, NavPhase.FAILED)
        const val MAX_PREVIEW_ROUTES = 3
        const val MS_PER_S = 1000.0

        /** About 100 m: a requested stop comes back from Directions at the coordinates it was given. */
        const val WAYPOINT_TOLERANCE = 1e-3
    }
}
