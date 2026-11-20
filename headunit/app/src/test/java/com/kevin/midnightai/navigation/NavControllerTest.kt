package com.kevin.midnightai.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU-1 T5 trip rules against a fake SDK: no token and no capability fall back with words, a
 * session runs only with a route, outcomes are read back from the SDK, the middle panel flips to
 * NAV when guidance starts and back to CAR on End or 30 s after arrival, and an SDK failure never
 * escapes (the launcher must not crash).
 */
class NavControllerTest {
    private class FakeSdk : NavSdk {
        override var listener: NavSdkListener? = null
        var route: RouteResult = RouteResult.Ready("token", NavRoute(600.0, 8_000.0, "Main St"))
        var takes = true
        var startThrows: Throwable? = null
        var running = false
        var stopKeepsRunning = false
        var requests = 0
        override fun requestRoute(origin: GeoPoint, destination: GeoPoint, onResult: (RouteResult) -> Unit) {
            requests++
            onResult(route)
        }
        override fun setRoute(token: Any, onDone: (Boolean) -> Unit) = onDone(takes)
        override fun startSession() {
            startThrows?.let { throw it }
            running = true
        }
        override fun stopAndClear() {
            if (!stopKeepsRunning) running = false
        }
        override fun isSessionRunning() = running
    }

    private val home = NavDestination("home", GeoPoint(29.76, -95.37))
    private var token = true
    private var unavailable: String? = null
    private var pos: GeoPoint? = GeoPoint(29.70, -95.40)
    private var started = 0
    private var ended = 0
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val sdk = FakeSdk()
    private var factoryThrows: Throwable? = null

    private fun controller() = NavController(
        sdkFactory = { factoryThrows?.let { throw it }; sdk },
        hasToken = { token },
        availability = { unavailable },
        position = { pos },
        onTripStarted = { started++ },
        onTripEnded = { ended++ },
        schedule = { ms, block -> scheduled += ms to block },
    )

    @Test fun noTokenSaysPairYourPhoneAndAsksNothing() {
        token = false
        val c = controller()
        var result: NavResult? = null
        c.navigateTo(home) { result = it }
        assertEquals(NavPhase.NO_TOKEN, c.state.value.phase)
        assertTrue(result!!.message.startsWith("Pair your phone to get maps"))
        assertEquals(0, sdk.requests)
    }

    @Test fun aUnitThatCannotRunTheMapStaysOnTheCarFaceWithWords() {
        unavailable = "its graphics chip is older than OpenGL ES 3"
        val c = controller()
        c.refresh()
        assertEquals(NavPhase.UNAVAILABLE, c.state.value.phase)
        assertTrue(c.state.value.message.contains("car face stays"))
        assertFalse(c.mapAllowed())
    }

    @Test fun anSdkThatFailsToStartNeverEscapes() {
        factoryThrows = UnsatisfiedLinkError("libmapbox-common.so not found")
        val c = controller()
        var result: NavResult? = null
        c.navigateTo(home) { result = it }
        assertEquals(NavPhase.UNAVAILABLE, c.state.value.phase)
        assertFalse(result!!.ok)
        assertTrue(result!!.message, result!!.message.contains("libmapbox-common.so"))
    }

    @Test fun previewThenGoStartsGuidanceAndFlipsToNav() {
        val c = controller()
        c.navigateTo(home)
        assertEquals(NavPhase.PREVIEW, c.state.value.phase)
        assertEquals(0, started)
        assertFalse("no session before GO (no Free Drive, no billing)", sdk.running)
        var result: NavResult? = null
        c.go { result = it }
        assertTrue(result!!.ok)
        assertEquals(NavPhase.GUIDING, c.state.value.phase)
        assertEquals(1, started)
    }

    @Test fun guidanceIsReportedOnlyIfTheSessionReallyRuns() {
        sdk.startThrows = SecurityException("location permission missing")
        val c = controller()
        c.navigateTo(home)
        var result: NavResult? = null
        c.go { result = it }
        assertFalse(result!!.ok)
        assertEquals(NavPhase.FAILED, c.state.value.phase)
        assertTrue(result!!.message.contains("location permission missing"))
        assertEquals(0, started)
    }

    /** Review item 7: the SDK's own failure text (URLs, tokens, positions) never reaches the screen or the voice model. */
    @Test fun aRouteFailureIsAFixedSentenceNeverTheSdksText() {
        // The real SDK's text would be "HTTP 401 GET https://api.mapbox.com/directions/...access_token=...";
        // MapboxNavSdk keeps only the kind.
        sdk.route = RouteResult.Failed(RouteFailure.TOKEN_REFUSED)
        val c = controller()
        var result: NavResult? = null
        c.navigateTo(home) { result = it }
        for (words in listOf(result!!.message, c.state.value.message)) {
            assertFalse(words, words.contains("api.mapbox.com") || words.contains("access_token") || words.contains("29.76"))
        }
        assertEquals(NavController.failureWords(RouteFailure.TOKEN_REFUSED, "home"), c.state.value.message)
    }

    @Test fun noPositionAsksForNoRoute() {
        pos = null
        val c = controller()
        c.navigateTo(home)
        assertEquals(NavPhase.FAILED, c.state.value.phase)
        assertEquals(0, sdk.requests)
    }

    @Test fun endStopsTheSessionAndFlipsBackToCar() {
        val c = controller()
        c.navigateTo(home)
        c.go()
        var result: NavResult? = null
        c.end { result = it }
        assertTrue(result!!.ok)
        assertFalse(sdk.running)
        assertEquals(1, ended)
        assertEquals(NavPhase.IDLE, c.state.value.phase)
    }

    @Test fun anEndTheSdkDidNotHonourIsNotReportedAsEnded() {
        val c = controller()
        c.navigateTo(home)
        c.go()
        sdk.stopKeepsRunning = true
        var result: NavResult? = null
        c.end { result = it }
        assertFalse(result!!.ok)
        assertEquals(0, ended)
    }

    @Test fun arrivalHoldsThirtySecondsThenGoesBackToCar() {
        val c = controller()
        c.navigateTo(home)
        c.go()
        sdk.listener!!.onProgress(NavProgress(30.0, 100.0, NavTurn("Arrive at home", 100.0, "arrive", null)))
        assertEquals(100.0, c.state.value.progress!!.distanceLeftM!!, 0.0)
        sdk.listener!!.onArrival()
        assertEquals(NavPhase.ARRIVED, c.state.value.phase)
        assertFalse(sdk.running)
        assertEquals(0, ended)
        assertEquals(NavController.ARRIVAL_HOLD_MS, scheduled.single().first)
        scheduled.single().second()
        assertEquals(1, ended)
        assertEquals(NavPhase.IDLE, c.state.value.phase)
    }

    @Test fun aMapFailureMidTripEndsTheTripWithWords() {
        val c = controller()
        c.navigateTo(home)
        c.go()
        c.mapFailed("the map style did not load")
        assertEquals(NavPhase.UNAVAILABLE, c.state.value.phase)
        assertEquals(1, ended)
        assertFalse(sdk.running)
    }

    @Test fun recenterFollowsAgainAfterTheDriverMovedTheMap() {
        val c = controller()
        c.mapMovedByHand()
        assertFalse(c.state.value.following)
        c.recenter()
        assertTrue(c.state.value.following)
    }
}
