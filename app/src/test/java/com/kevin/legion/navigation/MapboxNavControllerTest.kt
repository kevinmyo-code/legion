package com.kevin.legion.navigation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted stand-in for the Mapbox SDK, recording the order of every call that matters to billing. */
class FakeNavSdk : NavSdk {
    /** How many times a listener was attached; one per instance, or observers would double. */
    var listenerSets = 0
    override var listener: NavSdkListener? = null
        set(value) {
            if (value != null) listenerSets++
            field = value
        }

    /** Every call in order; `clear` records whether the session was still running when routes were cleared. */
    val calls = mutableListOf<String>()
    var sessionRunning = false
    var destroyed = false
    var refuseToStart = false
    var refuseToStop = false
    var failSetRoutes = false

    /** Answers a request at once. Null means hold it until [deliver]. */
    var autoReply: ((RouteRequest) -> RouteRequestResult)? = null
    private val pending = ArrayDeque<(RouteRequestResult) -> Unit>()
    private var navigatorRoutes: List<NavRouteInfo> = emptyList()
    private var nextId = 0L
    val requests = mutableListOf<RouteRequest>()

    override fun requestRoutes(request: RouteRequest, onResult: (RouteRequestResult) -> Unit): Long {
        calls += "request"
        requests += request
        val reply = autoReply
        if (reply != null) onResult(reply(request)) else pending += onResult
        return ++nextId
    }

    override fun cancelRequest(id: Long) {
        calls += "cancelRequest"
    }

    override fun showPreview(token: Any) {
        calls += "showPreview"
    }

    /** Answers the oldest held request. */
    fun deliver(result: RouteRequestResult) = pending.removeFirst()(result)

    @Suppress("UNCHECKED_CAST")
    override fun setRoutes(token: Any, primaryIndex: Int, onDone: (Boolean) -> Unit) {
        calls += "setRoutes"
        if (failSetRoutes) return onDone(false)
        val routes = token as List<NavRouteInfo>
        navigatorRoutes = listOf(routes[primaryIndex]) + routes.filterIndexed { i, _ -> i != primaryIndex }
        onDone(true)
    }

    override fun switchPrimary(index: Int, onDone: (Boolean) -> Unit) {
        calls += "switchPrimary"
        val target = navigatorRoutes.getOrNull(index) ?: return onDone(false)
        navigatorRoutes = listOf(target) + navigatorRoutes.filterIndexed { i, _ -> i != index }
        onDone(true)
    }

    override fun startSession() {
        calls += "startSession"
        if (!refuseToStart) sessionRunning = true
    }

    override fun stopSession() {
        calls += "stopSession"
        if (!refuseToStop) sessionRunning = false
    }

    override fun clearRoutes() {
        calls += "clearRoutes(sessionRunning=$sessionRunning)"
        navigatorRoutes = emptyList()
    }

    override fun destroy() {
        calls += "destroy"
        destroyed = true
    }

    override fun isSessionRunning() = sessionRunning

    override fun activeRoutes() = navigatorRoutes

    override fun primaryGeometry(): List<GeoPoint> =
        if (navigatorRoutes.isEmpty()) emptyList() else listOf(GeoPoint(29.0, -95.0), GeoPoint(29.1, -95.1))

    companion object {
        fun route(
            id: String,
            request: RouteRequest,
            durationS: Double = 1080.0,
            distanceM: Double = 15_000.0,
        ) = NavRouteInfo(
            id = id,
            durationS = durationS,
            distanceM = distanceM,
            typicalDurationS = null,
            via = "I-69 S",
            hasTolls = null,
            waypoints = request.stops.map { it.point } + request.destination.point,
            excluded = request.avoid,
            trafficSummary = "About as long as usual.",
        )

        /** Two routes honouring whatever the request asked for. */
        fun honest(request: RouteRequest): RouteRequestResult {
            val routes = listOf(route("r1", request), route("r2", request, 1260.0, 17_000.0))
            return RouteRequestResult.Ready(routes, routes)
        }
    }
}

class FakeTokens(token: String = "pk.test") : MapboxTokenSource {
    private val _state = MutableStateFlow(MapboxTokenState(token, rejected = false))
    override val state: StateFlow<MapboxTokenState> = _state

    fun set(token: String) {
        _state.value = MapboxTokenState(token, rejected = false)
    }

    override fun markRejected() {
        _state.value = _state.value.copy(rejected = true)
    }
}

class MapboxNavControllerTest {
    private val home = NavDestination("Home", 29.7, -95.4)
    private val shell = NavDestination("Shell on Westheimer", 29.74, -95.45)
    private val bank = NavDestination("Bank", 29.72, -95.41)
    private val here = GeoPoint(29.6, -95.3)

    private class Rig(
        val sdk: FakeNavSdk = FakeNavSdk(),
        val tokens: FakeTokens = FakeTokens(),
        var fix: GeoPoint? = GeoPoint(29.6, -95.3),
    ) {
        var created = 0
        val controller = MapboxNavController(
            tokens = tokens,
            sdkFactory = { created++; sdk },
            fix = { fix },
            nowMs = { 1_000_000L },
        )
    }

    private fun rig(setup: Rig.() -> Unit = {}): Rig = Rig().apply {
        sdk.autoReply = FakeNavSdk::honest
        setup()
    }

    private suspend fun guiding(r: Rig): MapboxNavController {
        assertTrue(r.controller.navigate(home).ok)
        return r.controller
    }

    // ------------------------------------------------------------------ preview / start

    @Test fun previewShowsRoutesAndStartsNothing() = runBlocking {
        val r = rig()
        val res = r.controller.preview(home)
        assertTrue(res.message, res.ok)
        assertTrue(res.message.contains("Nothing has started"))
        assertEquals(NavPhase.PREVIEW, r.controller.state.value.phase)
        assertEquals(2, r.controller.state.value.routes.size)
        assertFalse("a preview must not start a billed session", r.sdk.calls.contains("startSession"))
        assertFalse(r.sdk.sessionRunning)
    }

    @Test fun navigateIsPreviewThenStartAndSucceedsOnlyWithARunningSession() = runBlocking {
        val r = rig()
        val res = r.controller.navigate(home)
        assertTrue(res.message, res.ok)
        assertEquals(NavPhase.GUIDING, r.controller.state.value.phase)
        assertTrue(r.sdk.sessionRunning)
        // Routes are set before the session starts, and the session starts exactly once.
        assertEquals(listOf("request", "showPreview", "setRoutes", "startSession"), r.sdk.calls)
        assertTrue(res.message.startsWith("Navigating to Home"))
    }

    @Test fun navigatePreviewOnlyStopsAtThePreview() = runBlocking {
        val r = rig()
        val res = r.controller.navigate(home, previewOnly = true)
        assertTrue(res.ok)
        assertEquals(NavPhase.PREVIEW, r.controller.state.value.phase)
        assertFalse(r.sdk.calls.contains("startSession"))
    }

    @Test fun startThatTheSdkDoesNotRunIsNotSuccess() = runBlocking {
        val r = rig { sdk.refuseToStart = true }
        r.controller.preview(home)
        val res = r.controller.start()
        assertFalse("the call was made but no session is running", res.ok)
        assertEquals(NavPhase.FAILED, r.controller.state.value.phase)
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
        assertTrue(res.message.contains("nothing is navigating"))
    }

    @Test fun routesThatCouldNotBeSetAreNotSuccess() = runBlocking {
        val r = rig { sdk.failSetRoutes = true }
        r.controller.preview(home)
        assertFalse(r.controller.start().ok)
        assertFalse("no session after a failed set", r.sdk.calls.contains("startSession"))
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
    }

    @Test fun startWithNothingPreviewedSaysSo() = runBlocking {
        val res = rig().controller.start()
        assertFalse(res.ok)
        assertTrue(res.message.contains("no route ready"))
    }

    @Test fun startingTwiceIsAlreadyNavigatingNotASecondSession() = runBlocking {
        val r = rig()
        guiding(r)
        val again = r.controller.start()
        assertTrue(again.message, again.ok)
        assertEquals(1, r.sdk.calls.count { it == "startSession" })
    }

    @Test fun aSecondDestinationWhileGuidingIsRefusedAndTheTripIsUntouched() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.preview(bank)
        assertFalse(res.ok)
        assertTrue(res.message.contains("Already navigating to Home"))
        assertEquals(NavPhase.GUIDING, r.controller.state.value.phase)
        assertEquals(1, r.sdk.calls.count { it == "request" })
    }

    @Test fun noFixMeansNoRequestAndSaysWhy() = runBlocking {
        val r = rig { fix = null }
        val res = r.controller.preview(home)
        assertFalse(res.ok)
        assertTrue(res.message.contains("No location fix"))
        assertEquals(NavPhase.FAILED, r.controller.state.value.phase)
        assertEquals(0, r.created)
    }

    @Test fun noTokenCreatesNoSdkAndSaysNotSetUp() = runBlocking {
        val r = rig { tokens.set("") }
        val res = r.controller.preview(home)
        assertFalse(res.ok)
        assertTrue(res.message.contains("isn't set up"))
        assertEquals(NavPhase.NOT_SET_UP, r.controller.state.value.phase)
        assertEquals(0, r.created)
    }

    @Test fun routeFailureIsSaidInWordsAndNothingIsLeftRunning() = runBlocking {
        val r = rig { sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.OFFLINE, "UnknownHost") } }
        val res = r.controller.preview(home)
        assertFalse(res.ok)
        assertTrue(res.message.startsWith("No connection"))
        assertEquals(NavPhase.FAILED, r.controller.state.value.phase)
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
        assertFalse(r.sdk.sessionRunning)
    }

    @Test fun anAuthFailureMarksTheTokenRefused() = runBlocking {
        val r = rig { sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.AUTH, "401") } }
        val res = r.controller.preview(home)
        assertFalse(res.ok)
        assertTrue(res.message.contains("refused the token"))
        assertEquals(NavPhase.TOKEN_REFUSED, r.controller.state.value.phase)
        assertTrue(r.tokens.state.value.rejected)
    }

    @Test fun anEmptyRouteAnswerIsAFailureNotAPreview() = runBlocking {
        val r = rig { sdk.autoReply = { RouteRequestResult.Ready(emptyList<NavRouteInfo>(), emptyList()) } }
        val res = r.controller.preview(home)
        assertFalse(res.ok)
        assertTrue(res.message.contains("no route"))
        assertEquals(NavPhase.FAILED, r.controller.state.value.phase)
    }

    // ------------------------------------------------------------------ billing: end, arrival, screen left

    @Test fun endStopsTheSessionBeforeClearingRoutesAndKeepsTheInstance() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.calls.clear()
        val res = r.controller.end()
        assertTrue(res.message, res.ok)
        assertEquals(listOf("stopSession", "clearRoutes(sessionRunning=false)"), r.sdk.calls)
        assertEquals(NavPhase.ENDED, r.controller.state.value.phase)
        assertFalse(r.sdk.sessionRunning)
        assertTrue(r.controller.state.value.message.contains("Nothing is navigating"))
    }

    @Test fun endIsOnlySuccessWhenTheSdkConfirmsTheSessionStopped() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.refuseToStop = true
        val res = r.controller.end()
        assertFalse(res.ok)
        assertTrue(res.message.contains("could not confirm"))
        assertFalse("the instance is kept; the session stop is what ends billing", r.sdk.destroyed)
    }

    @Test fun endWithNoTripSaysNothingToEnd() = runBlocking {
        val r = rig()
        val res = r.controller.end()
        assertTrue(res.ok)
        assertEquals("Nothing to end: no trip was running.", res.message)
        assertEquals(0, r.created)
    }

    @Test fun endingAPreviewSaysItClearedThePreviewNotThatItEndedATrip() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        val res = r.controller.end()
        assertTrue(res.message.startsWith("No trip was running; I cleared the route preview"))
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
        assertFalse(r.sdk.calls.contains("startSession"))
    }

    @Test fun arrivalStopsTheSessionAndMovesToArrived() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.calls.clear()
        r.controller.onArrival()
        assertEquals(NavPhase.ARRIVED, r.controller.state.value.phase)
        assertEquals(listOf("stopSession", "clearRoutes(sessionRunning=false)"), r.sdk.calls)
        assertTrue(r.controller.state.value.message.contains("arrived at Home"))
        r.controller.onArrival()
        assertEquals("a second arrival signal does nothing", 2, r.sdk.calls.size)
    }

    @Test fun aCompleteProgressStateIsArrivalToo() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onProgress(NavProgressInfo(0.0, 0.0, null, null, remainingWaypoints = 1, complete = true))
        assertEquals(NavPhase.ARRIVED, r.controller.state.value.phase)
    }

    @Test fun leavingTheScreenWhileGuidingKeepsTheTripRunning() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.calls.clear()
        r.controller.onScreenLeft()
        assertEquals(NavPhase.GUIDING, r.controller.state.value.phase)
        assertTrue(r.sdk.sessionRunning)
        assertFalse(r.sdk.destroyed)
        assertTrue("nothing was stopped, cleared or destroyed", r.sdk.calls.isEmpty())
    }

    @Test fun leavingTheScreenDropsAPreviewWithoutAnySession() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        r.controller.onScreenLeft()
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
        assertFalse(r.sdk.calls.contains("startSession"))
    }

    @Test fun leavingAfterArrivalClearsTheVerdict() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onArrival()
        r.controller.onScreenLeft()
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
    }

    @Test fun aLateRouteResponseAfterEndStartsNothing() = runBlocking {
        val r = rig { sdk.autoReply = null }
        val job = async(start = CoroutineStart.UNDISPATCHED) { r.controller.preview(home) }
        assertEquals(NavPhase.REQUESTING, r.controller.state.value.phase)
        r.controller.end()
        // The SDK answers anyway, after the user already ended it.
        r.sdk.deliver(FakeNavSdk.honest(RouteRequest(here, emptyList(), home, emptySet())))
        val res = job.await()
        assertFalse(res.ok)
        assertTrue(res.message.contains("superseded"))
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
        assertFalse(r.sdk.calls.contains("startSession"))
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
    }

    @Test fun aCancelledCallerLeavesNoRequestOrSessionBehind() = runBlocking {
        val r = rig { sdk.autoReply = null }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { r.controller.preview(home) }
        assertEquals(NavPhase.REQUESTING, r.controller.state.value.phase)
        job.cancelAndJoin()
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
        assertTrue(r.sdk.calls.contains("cancelRequest"))
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
        assertFalse(r.sdk.sessionRunning)
    }

    // ------------------------------------------------------------------ token

    @Test fun anUnchangedTokenDoesNotEndARunningTrip() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onTokenChanged()
        assertEquals(NavPhase.GUIDING, r.controller.state.value.phase)
        assertTrue(r.sdk.sessionRunning)
    }

    @Test fun aChangedTokenEndsTheTripAndReReadsTheState() = runBlocking {
        val r = rig()
        guiding(r)
        r.tokens.set("")
        r.controller.onTokenChanged()
        assertEquals(NavPhase.NOT_SET_UP, r.controller.state.value.phase)
        assertFalse(r.sdk.sessionRunning)
        assertTrue(r.sdk.destroyed)
    }

    @Test fun aMapAuthFailureTearsDownAndSaysRefused() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onTokenRefused()
        assertEquals(NavPhase.TOKEN_REFUSED, r.controller.state.value.phase)
        assertFalse(r.sdk.sessionRunning)
        assertTrue(r.tokens.state.value.rejected)
    }

    // ------------------------------------------------------------------ stops

    @Test fun addStopSucceedsOnlyWhenTheNewRouteGoesThroughIt() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.addStop(shell)
        assertTrue(res.message, res.ok)
        assertEquals(listOf(shell), r.controller.state.value.stops)
        assertTrue(res.message.startsWith("Added Shell on Westheimer as a stop"))
        assertEquals("rebuilt from the live fix through the stop", shell, r.sdk.requests.last().stops.single())
        assertEquals(here, r.sdk.requests.last().origin)
        assertEquals("the session was never stopped for a rebuild", 0, r.sdk.calls.count { it == "stopSession" })
        assertTrue(r.sdk.sessionRunning)
    }

    @Test fun addStopThatTheRouteIgnoresLeavesTheTripUnchangedAndSaysSo() = runBlocking {
        val r = rig()
        guiding(r)
        // Mapbox answers with a route that does not pass through the stop.
        r.sdk.autoReply = { req ->
            val bad = listOf(FakeNavSdk.route("x", req).copy(waypoints = listOf(home.point)))
            RouteRequestResult.Ready(bad, bad)
        }
        val res = r.controller.addStop(shell)
        assertFalse(res.ok)
        assertTrue(res.message.startsWith("Trip unchanged, still going to Home."))
        assertTrue(r.controller.state.value.stops.isEmpty())
    }

    @Test fun addStopThatFailsToRouteIsUnchanged() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.NO_ROUTE, null) }
        val res = r.controller.addStop(shell)
        assertFalse(res.ok)
        assertTrue(res.message.contains("Trip unchanged"))
        assertTrue(res.message.contains("no drivable route"))
        assertEquals(NavPhase.GUIDING, r.controller.state.value.phase)
        assertTrue(r.sdk.sessionRunning)
    }

    @Test fun addStopWhenTheNewRoutesCannotBeSetKeepsTheOldTrip() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.failSetRoutes = true
        val res = r.controller.addStop(shell)
        assertFalse(res.ok)
        assertTrue(res.message.contains("could not confirm"))
        assertTrue(r.controller.state.value.stops.isEmpty())
    }

    @Test fun removeStopDropsItAndConfirmsTheRouteLostIt() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.addStop(shell)
        val res = r.controller.removeStop("shell")
        assertTrue(res.message, res.ok)
        assertTrue(r.controller.state.value.stops.isEmpty())
        assertTrue(res.message.startsWith("Dropped Shell"))
    }

    @Test fun removeStopWithSeveralAsksWhich() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.addStop(shell)
        r.controller.addStop(bank)
        val res = r.controller.removeStop(null)
        assertFalse(res.ok)
        assertTrue(res.message.contains("Which one?"))
        assertEquals(2, r.controller.state.value.stops.size)
    }

    @Test fun removeStopWithNoneSaysSo() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.removeStop(null)
        assertFalse(res.ok)
        assertTrue(res.message.contains("no extra stops"))
    }

    @Test fun aStopAddedInPreviewRebuildsThePreviewWithoutAnySession() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        val res = r.controller.addStop(shell)
        assertTrue(res.message, res.ok)
        assertEquals(NavPhase.PREVIEW, r.controller.state.value.phase)
        assertEquals(listOf(shell), r.controller.state.value.stops)
        assertFalse(r.sdk.calls.contains("startSession"))
    }

    @Test fun aFailedChangeInPreviewKeepsTheStandingPreview() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        r.sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.OFFLINE, null) }
        val res = r.controller.addStop(shell)
        assertFalse(res.ok)
        assertTrue(res.message.startsWith("Preview unchanged"))
        assertEquals(NavPhase.PREVIEW, r.controller.state.value.phase)
        assertEquals(2, r.controller.state.value.routes.size)
    }

    @Test fun passedStopsDropOffAsTheTripProgresses() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.addStop(shell)
        r.controller.onProgress(NavProgressInfo(900.0, 9000.0, null, null, remainingWaypoints = 1, complete = false))
        assertTrue("one waypoint left is the destination: no stops remain", r.controller.state.value.stops.isEmpty())
    }

    @Test fun changesWithNoTripAreRefusedInWords() = runBlocking {
        val r = rig()
        val refused = listOf(
            r.controller.addStop(shell),
            r.controller.setAvoid(setOf(AvoidKind.TOLLS)),
            r.controller.takeAlternative(1),
        )
        for (res in refused) {
            assertFalse(res.ok)
            assertTrue(res.message.contains("Nothing is navigating"))
        }
    }

    // ------------------------------------------------------------------ avoid

    @Test fun setAvoidIsSuccessOnlyWhenTheRouteCarriesTheExclusion() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.setAvoid(setOf(AvoidKind.TOLLS))
        assertTrue(res.message, res.ok)
        assertEquals(setOf(AvoidKind.TOLLS), r.controller.state.value.avoid)
        assertEquals(setOf(AvoidKind.TOLLS), r.sdk.requests.last().avoid)
        assertTrue(res.message.startsWith("Avoiding tolls"))
    }

    @Test fun setAvoidThatMapboxDidNotApplyIsNotSuccess() = runBlocking {
        val r = rig()
        guiding(r)
        r.sdk.autoReply = { req ->
            val bad = listOf(FakeNavSdk.route("x", req).copy(excluded = emptySet()))
            RouteRequestResult.Ready(bad, bad)
        }
        val res = r.controller.setAvoid(setOf(AvoidKind.TOLLS))
        assertFalse(res.ok)
        assertTrue(r.controller.state.value.avoid.isEmpty())
    }

    @Test fun settingTheSameAvoidAgainChangesNothing() = runBlocking {
        val r = rig()
        guiding(r)
        val before = r.sdk.requests.size
        val res = r.controller.setAvoid(emptySet())
        assertTrue(res.ok)
        assertTrue(res.message.contains("Nothing changed"))
        assertEquals(before, r.sdk.requests.size)
    }

    @Test fun toggleAvoidFlipsOneKindAndKeepsTheRest() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.setAvoid(setOf(AvoidKind.TOLLS))
        r.controller.toggleAvoid(AvoidKind.FERRIES)
        assertEquals(setOf(AvoidKind.TOLLS, AvoidKind.FERRIES), r.controller.state.value.avoid)
        r.controller.toggleAvoid(AvoidKind.TOLLS)
        assertEquals(setOf(AvoidKind.FERRIES), r.controller.state.value.avoid)
    }

    // ------------------------------------------------------------------ alternatives, camera, mute

    @Test fun takeAlternativeInPreviewChangesWhichRouteStartUses() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        val res = r.controller.takeAlternative(1)
        assertTrue(res.message, res.ok)
        assertEquals(1, r.controller.state.value.selectedRoute)
        r.controller.start()
        assertEquals("r2", r.sdk.activeRoutes().first().id)
    }

    @Test fun takeAlternativeWhileGuidingIsSuccessOnlyWhenThePrimaryChanged() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.takeAlternative(1)
        assertTrue(res.message, res.ok)
        assertEquals("r2", r.controller.state.value.routes.first().id)
        assertEquals(0, r.controller.state.value.selectedRoute)
        assertTrue(r.sdk.sessionRunning)
    }

    @Test fun takeAlternativeOutOfRangeChangesNothing() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.takeAlternative(5)
        assertFalse(res.ok)
        assertTrue(res.message.contains("Nothing changed"))
    }

    @Test fun takingTheRouteAlreadyInUseIsSaidNotRepeated() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.takeAlternative(0)
        assertTrue(res.ok)
        assertTrue(res.message.contains("already the route in use"))
        assertEquals(0, r.sdk.calls.count { it == "switchPrimary" })
    }

    @Test fun muteIsStateOnlyAndSurvivesATrip() = runBlocking {
        val r = rig()
        assertTrue(r.controller.setMuted(true).ok)
        assertTrue(r.controller.state.value.muted)
        guiding(r)
        assertTrue("muted survives starting a trip", r.controller.state.value.muted)
        r.controller.end()
        assertTrue("and ending one", r.controller.state.value.muted)
        assertEquals(0, r.sdk.calls.count { it.contains("mute") })
        assertEquals("Turn cues are on.", r.controller.setMuted(false).message)
    }

    @Test fun overviewAndRecenterNeedARouteAndSetTheCameraMode() = runBlocking {
        val r = rig()
        assertFalse(r.controller.overview().ok)
        guiding(r)
        assertTrue(r.controller.overview().ok)
        assertEquals(NavCameraMode.OVERVIEW, r.controller.state.value.camera)
        assertTrue(r.controller.recenter().ok)
        assertEquals(NavCameraMode.FOLLOWING, r.controller.state.value.camera)
        r.controller.cameraDetached()
        assertEquals(NavCameraMode.FREE, r.controller.state.value.camera)
    }

    // ------------------------------------------------------------------ status

    @Test fun statusWithNoTripIsNotNavigatingNeverZeros() = runBlocking {
        val r = rig()
        val s = r.controller.status()
        assertTrue(s is TripStatus.NotNavigating)
        assertEquals("Not navigating.", (s as TripStatus.NotNavigating).message)
    }

    @Test fun statusInPreviewIsStillNotNavigating() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        val s = r.controller.status() as TripStatus.NotNavigating
        assertTrue(s.message.contains("previewed but has not been started"))
    }

    @Test fun statusWhileGuidingCarriesUnknownsAsNullNotZero() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onProgress(NavProgressInfo(600.0, 8000.0, null, null, remainingWaypoints = 1, complete = false))
        r.controller.onLocation(road = null, speedLimit = null)
        val s = r.controller.status() as TripStatus.Navigating
        assertEquals("Home", s.destination.name)
        assertEquals(600.0, s.guidance.durationLeftS!!, 0.0)
        assertNull("no banner yet is unknown", s.guidance.turn)
        assertNull("no road is unknown", s.guidance.road)
        assertNull("a road with no posted limit is unknown, not 0", s.guidance.speedLimit)
        assertEquals(1_000_000L + 600_000L, s.guidance.arrivalAtMs)
        assertEquals("About as long as usual.", s.guidance.traffic)
    }

    @Test fun statusReflectsLiveGuidance() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onProgress(
            NavProgressInfo(
                500.0,
                7000.0,
                NavTurn("Turn left onto Kirby Dr", 480.0, "turn", "left"),
                "merge onto I-69 S",
                1,
                false,
            ),
        )
        r.controller.onLocation("Kirby Dr", SpeedLimit(35, SpeedUnit.MPH))
        val g = (r.controller.status() as TripStatus.Navigating).guidance
        assertEquals("Turn left onto Kirby Dr", g.turn?.text)
        assertEquals("merge onto I-69 S", g.then)
        assertEquals("Kirby Dr", g.road)
        assertEquals(SpeedLimit(35, SpeedUnit.MPH), g.speedLimit)
    }

    @Test fun afterEndStatusIsNotNavigating() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.end()
        val s = r.controller.status() as TripStatus.NotNavigating
        assertTrue(s.message.contains("ended"))
    }

    @Test fun rerouteStateShowsAndClears() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onReroute(RerouteStatus.FETCHING, null)
        assertTrue(r.controller.state.value.rerouting)
        r.controller.onReroute(RerouteStatus.FAILED, "no connection")
        assertFalse(r.controller.state.value.rerouting)
        assertTrue(r.controller.state.value.notice!!.contains("Could not find a new route"))
        r.controller.onReroute(RerouteStatus.IDLE, null)
        assertNull(r.controller.state.value.notice)
    }

    @Test fun sdkCallbacksAfterTheTripIsOverAreIgnored() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.end()
        val before = r.controller.state.value
        r.controller.onProgress(NavProgressInfo(1.0, 1.0, null, null, 1, false))
        r.controller.onLocation("X", null)
        r.controller.onReroute(RerouteStatus.FETCHING, null)
        assertEquals(before, r.controller.state.value)
    }

    @Test fun dismissClearsAnArrivedSheet() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onArrival()
        r.controller.dismiss()
        assertEquals(NavPhase.IDLE, r.controller.state.value.phase)
    }

    // ---- device-run fixes (2026-10-03)

    @Test fun endedTripSaysHowFarWasLeftInWords() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.onProgress(NavProgressInfo(500.0, 186_700.0, null, null, 1, false))
        r.controller.end()
        val m = r.controller.state.value.message
        assertEquals("Trip ended with ${NavFormat.distance(186_700.0)} to go. Nothing is navigating.", m)
        assertFalse(m, m.contains("short"))
    }

    @Test fun addingAStopAfterPickingAnAlternativeSaysTheAlternativeWasReplaced() = runBlocking {
        val r = rig()
        guiding(r)
        assertTrue(r.controller.takeAlternative(1).ok)
        val res = r.controller.addStop(shell)
        assertTrue(res.message, res.ok)
        assertTrue(res.message, res.message.contains("alternative you picked was replaced"))
        assertTrue(r.controller.state.value.note.orEmpty().contains("replaced"))
    }

    @Test fun addingAStopWithTheDefaultRouteInUseSaysNothingAboutAReplacement() = runBlocking {
        val r = rig()
        guiding(r)
        val res = r.controller.addStop(shell)
        assertTrue(res.message, res.ok)
        assertFalse(res.message.contains("replaced"))
        assertNull(r.controller.state.value.note)
    }

    @Test fun anAlternativePickedInPreviewAndCarriedIntoGuidingIsStillReportedWhenReplaced() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        r.controller.takeAlternative(1)
        assertTrue(r.controller.start().ok)
        val res = r.controller.addStop(shell)
        assertTrue(res.message, res.message.contains("replaced"))
    }

    @Test fun theReplacementIsSaidOnceNotOnEveryLaterChange() = runBlocking {
        val r = rig()
        guiding(r)
        r.controller.takeAlternative(1)
        r.controller.addStop(shell)
        val again = r.controller.removeStop()
        assertFalse(again.message, again.message.contains("replaced"))
    }

    @Test fun theSdkIsCreatedOncePerTokenAndTheSessionIsBoundedByGuidingAcrossManyTrips() = runBlocking {
        // Device-run defect 13 (second run): a MapboxNavigation per trip leaked a native router each time.
        val r = rig()
        repeat(6) {
            assertTrue(r.controller.navigate(home).ok)
            assertTrue("a session runs while GUIDING", r.sdk.sessionRunning)
            r.controller.addStop(shell)
            assertTrue(r.controller.end().ok)
            assertFalse("no session once the trip is over", r.sdk.sessionRunning)
            r.controller.end()
            r.controller.onScreenLeft()
            assertEquals(1, r.created)
            assertFalse(r.sdk.destroyed)
        }
        // A trip that ends by arrival rather than End takes the same road.
        assertTrue(r.controller.navigate(home).ok)
        r.controller.onArrival()
        assertFalse(r.sdk.sessionRunning)
        assertEquals(1, r.created)
        assertFalse(r.sdk.destroyed)
    }

    @Test fun theSessionNeverRunsOutsideGuidingAndIsStoppedBeforeRoutesClear() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        assertFalse("preview is not a trip", r.sdk.sessionRunning)
        r.controller.start()
        r.controller.end()
        r.controller.preview(home)
        r.controller.onScreenLeft()
        assertFalse(r.sdk.sessionRunning)
        r.sdk.calls.filter { it.startsWith("clearRoutes") }.forEach {
            assertEquals("routes are never cleared under a live session", "clearRoutes(sessionRunning=false)", it)
        }
        assertEquals("one start per trip, never a free drive", 1, r.sdk.calls.count { it == "startSession" })
    }

    @Test fun aChangedTokenRebuildsTheSdkAndOnlyThen() = runBlocking {
        val sdks = mutableListOf<FakeNavSdk>()
        val tokens = FakeTokens("pk.one")
        val c = MapboxNavController(
            tokens,
            { FakeNavSdk().apply { autoReply = FakeNavSdk::honest }.also { sdks += it } },
            { GeoPoint(29.6, -95.3) },
        )
        assertTrue(c.navigate(home).ok)
        c.end()
        assertTrue(c.navigate(home).ok)
        c.end()
        assertEquals("same token, one instance", 1, sdks.size)
        tokens.set("pk.two")
        c.onTokenChanged()
        assertTrue("the old instance is destroyed on a token change", sdks[0].destroyed)
        assertTrue(c.navigate(home).ok)
        assertEquals("a new token builds a new instance", 2, sdks.size)
        assertFalse(sdks[1].destroyed)
        assertTrue(sdks[1].sessionRunning)
    }

    @Test fun aTokenChangeNoScreenNoticedStillRebuildsOnTheNextRequest() = runBlocking {
        val sdks = mutableListOf<FakeNavSdk>()
        val tokens = FakeTokens("pk.one")
        val c = MapboxNavController(
            tokens,
            { FakeNavSdk().apply { autoReply = FakeNavSdk::honest }.also { sdks += it } },
            { GeoPoint(29.6, -95.3) },
        )
        assertTrue(c.preview(home).ok)
        c.end()
        tokens.set("pk.two") // nobody called onTokenChanged: voice with the screen closed
        assertTrue(c.preview(home).ok)
        assertEquals(2, sdks.size)
        assertTrue(sdks[0].destroyed)
    }

    @Test fun aSecondTripDoesNotSpeakEachCueTwice() = runBlocking {
        val r = rig()
        val spoken = mutableListOf<String>()
        r.controller.cueSink = { spoken += it }
        repeat(2) { trip ->
            assertTrue(r.controller.navigate(home).ok)
            // The SDK fires one cue to whatever listener it holds: a re-attached or duplicated
            // listener would show up here as a second delivery.
            r.sdk.listener?.onVoiceInstruction("Turn left on trip $trip")
            assertEquals(1, spoken.count { it == "Turn left on trip $trip" })
            r.controller.end()
        }
        assertEquals(2, spoken.size)
        assertEquals("one listener attach for the whole process", 1, r.sdk.listenerSets)
    }

    @Test fun aPreviewThatIsBackedOutOfKeepsItsSdk() = runBlocking {
        val r = rig()
        r.controller.preview(home)
        r.controller.preview(home)
        assertEquals("a second preview reuses the one instance", 1, r.created)
        r.controller.end()
        assertFalse("the instance outlives the trip", r.sdk.destroyed)
    }
}
