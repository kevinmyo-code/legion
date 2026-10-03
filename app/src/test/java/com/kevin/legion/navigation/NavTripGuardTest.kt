package com.kevin.legion.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NavTripGuardTest {
    private fun previewing(): NavTripGuard {
        val g = NavTripGuard()
        g.requestStarted()
        g.routesReady(2)
        return g
    }

    private fun guiding(): NavTripGuard = previewing().also { it.startRequested() }

    @Test fun sessionRunsOnlyAfterStartFromAPreview() {
        val g = NavTripGuard()
        assertFalse(g.sessionShouldRun)
        assertTrue(g.requestStarted())
        assertFalse("requesting is not a trip", g.sessionShouldRun)
        assertTrue(g.routesReady(1))
        assertEquals(NavPhase.PREVIEW, g.phase)
        assertFalse("a preview is not a trip either", g.sessionShouldRun)
        assertTrue(g.startRequested())
        assertTrue(g.sessionShouldRun)
        assertEquals(NavPhase.GUIDING, g.phase)
    }

    @Test fun emptyRouteAnswerNeverReachesAPreview() {
        val g = NavTripGuard()
        g.requestStarted()
        assertFalse(g.routesReady(0))
        assertEquals(NavPhase.FAILED, g.phase)
        assertFalse(g.sessionShouldRun)
    }

    @Test fun routesWithNoRequestInFlightAreRefused() {
        val g = NavTripGuard()
        assertFalse("no request, so no preview", g.routesReady(2))
        assertEquals(NavPhase.IDLE, g.phase)
    }

    @Test fun startOnlyFromAPreview() {
        val g = NavTripGuard()
        assertFalse(g.startRequested())
        g.requestStarted()
        assertFalse("still requesting", g.startRequested())
        g.routesReady(1)
        assertTrue(g.startRequested())
        assertFalse("already guiding", g.startRequested())
    }

    @Test fun endWhileRequestingBlocksTheLateRouteResponse() {
        val g = NavTripGuard()
        g.requestStarted()
        assertFalse("no session was running to stop", g.endRequested())
        assertEquals(NavPhase.IDLE, g.phase)
        assertFalse("a late response must not show a preview", g.routesReady(1))
        assertFalse(g.startRequested())
    }

    @Test fun endWhileGuidingSaysStopTheSession() {
        val g = guiding()
        assertTrue(g.endRequested())
        assertFalse(g.sessionShouldRun)
        assertEquals(NavPhase.ENDED, g.phase)
    }

    @Test fun endingAPreviewIsNotATripEnd() {
        val g = previewing()
        assertFalse(g.endRequested())
        assertEquals(NavPhase.IDLE, g.phase)
    }

    @Test fun arrivalStopsTheSessionAndKeepsTheVerdict() {
        val g = guiding()
        assertTrue(g.arrived())
        assertEquals(NavPhase.ARRIVED, g.phase)
        assertFalse(g.sessionShouldRun)
        assertFalse("second arrival signal is a no-op", g.arrived())
    }

    @Test fun arrivalWhenNothingGuidesChangesNothing() {
        val g = previewing()
        assertFalse(g.arrived())
        assertEquals(NavPhase.PREVIEW, g.phase)
    }

    @Test fun screenLeavingWhileGuidingLeavesTheTripAlone() {
        val g = guiding()
        assertFalse("it must never ask for the session to be stopped", g.screenLeft())
        assertTrue(g.sessionShouldRun)
        assertEquals(NavPhase.GUIDING, g.phase)
    }

    @Test fun screenLeavingDropsAPreviewAndAFinishedVerdict() {
        val p = previewing()
        p.screenLeft()
        assertEquals(NavPhase.IDLE, p.phase)
        val a = guiding().also { it.arrived() }
        a.screenLeft()
        assertEquals(NavPhase.IDLE, a.phase)
    }

    @Test fun aSecondRequestWhileOneRunsIsRefusedButAPreviewMayBeReplaced() {
        val g = NavTripGuard()
        assertTrue(g.requestStarted())
        assertFalse(g.requestStarted())
        g.routesReady(1)
        assertTrue("changing a stop in preview re-requests", g.requestStarted())
        g.routesReady(1)
        g.startRequested()
        assertFalse("not while guiding", g.requestStarted())
    }

    @Test fun aFailedChangeGoesBackToTheStandingPreview() {
        val g = previewing()
        g.requestStarted()
        g.abandonRequestToPreview()
        assertEquals(NavPhase.PREVIEW, g.phase)
        g.abandonRequestToPreview()
        assertEquals("no-op outside REQUESTING", NavPhase.PREVIEW, g.phase)
    }

    @Test fun canRequestAgainAfterFailureArrivalOrEnd() {
        val g = NavTripGuard()
        g.requestStarted()
        g.requestFailed()
        assertEquals(NavPhase.FAILED, g.phase)
        assertTrue(g.requestStarted())
        g.routesReady(1)
        g.startRequested()
        g.arrived()
        assertTrue(g.requestStarted())
        g.routesReady(1)
        g.startRequested()
        g.endRequested()
        assertTrue(g.requestStarted())
    }

    @Test fun dismissedClearsOnlyFinishedVerdicts() {
        val g = guiding()
        g.dismissed()
        assertEquals("a running trip survives Done", NavPhase.GUIDING, g.phase)
        g.arrived()
        g.dismissed()
        assertEquals(NavPhase.IDLE, g.phase)
    }

    @Test fun startFailingFallsOutOfGuiding() {
        val g = previewing()
        g.startRequested()
        g.startFailed()
        assertFalse(g.sessionShouldRun)
        assertEquals(NavPhase.FAILED, g.phase)
    }

    @Test fun tokenChangeEndsARunningTripAndReportsIt() {
        val g = guiding()
        assertTrue(g.tokenChanged())
        assertEquals(NavPhase.IDLE, g.phase)
        assertFalse(NavTripGuard().tokenChanged())
    }
}

class NavFormatTest {
    private fun route(id: String, dur: Double = 600.0, dist: Double = 5000.0, tolls: Boolean? = null) = NavRouteInfo(
        id = id, durationS = dur, distanceM = dist, typicalDurationS = null, via = null, hasTolls = tolls,
        waypoints = emptyList(), excluded = emptySet(), trafficSummary = null,
    )

    @Test fun blankTokenIsNotSetUp() {
        assertFalse(NavFormat.hasToken(null))
        assertFalse(NavFormat.hasToken(""))
        assertFalse(NavFormat.hasToken("   "))
        assertTrue(NavFormat.hasToken("pk.abc"))
    }

    @Test fun notSetUpSaysSoInWords() {
        assertTrue(NavFormat.NOT_SET_UP.contains("isn't set up"))
    }

    @Test fun distanceFormats() {
        assertEquals("50 m", NavFormat.distance(52.0))
        assertEquals("1.0 mi", NavFormat.distance(1609.344))
        assertEquals("12 mi", NavFormat.distance(19312.0))
        assertEquals("unknown distance", NavFormat.distance(-1.0))
    }

    @Test fun durationFormats() {
        assertEquals("under 1 min", NavFormat.duration(20.0))
        assertEquals("12 min", NavFormat.duration(720.0))
        assertEquals("1 h 5 min", NavFormat.duration(3900.0))
    }

    @Test fun arrivalClockUsesTheGivenZone() {
        // 2026-10-03T23:42:00Z is 6:42 PM in Houston (CDT, UTC-5).
        assertEquals("6:42 PM", NavFormat.arrivalClock(1_791_070_920_000L, ZoneId.of("America/Chicago")))
    }

    @Test fun speedLimitIsNullWhenUnknownNeverZero() {
        assertNull(NavFormat.speedLimit(null))
        assertEquals("35 mph", NavFormat.speedLimit(SpeedLimit(35, SpeedUnit.MPH)))
        assertEquals("90 km/h", NavFormat.speedLimit(SpeedLimit(90, SpeedUnit.KPH)))
    }

    @Test fun routeLabelsNameTheFastestTheTollFreeAndTheShortest() {
        val labels = NavFormat.routeLabels(
            listOf(
                route("a", 600.0, 9000.0, tolls = true),
                route("b", 700.0, 9500.0, tolls = false),
                route("c", 800.0, 8000.0, tolls = true),
            ),
        )
        assertEquals(listOf("Fastest", "No tolls", "Shortest"), labels)
    }

    @Test fun unknownTollStatusNeverEarnsNoTolls() {
        val labels = NavFormat.routeLabels(listOf(route("a", tolls = true), route("b", 700.0, 9000.0, tolls = null)))
        assertEquals("Alternative", labels[1])
    }

    @Test fun routeDetailSaysTollsOnlyWhenKnown() {
        assertEquals("via I-69 S · has tolls", NavFormat.routeDetail(route("a", tolls = true).copy(via = "I-69 S")))
        assertEquals("via Kirby Dr", NavFormat.routeDetail(route("a", tolls = null).copy(via = "Kirby Dr")))
        assertEquals("roads unknown", NavFormat.routeDetail(route("a")))
    }

    @Test fun failureKindsAreBucketedByWording() {
        assertEquals(RouteFailure.AUTH, NavFormat.classifyFailure(null, "Not Authorized - Invalid Token", null))
        assertEquals(RouteFailure.OFFLINE, NavFormat.classifyFailure(null, "x", "UnknownHostException"))
        assertEquals(RouteFailure.OFFLINE, NavFormat.classifyFailure("NetworkError", "timeout", null))
        assertEquals(RouteFailure.NO_ROUTE, NavFormat.classifyFailure(null, "NoRoute", null))
        assertEquals(RouteFailure.OTHER, NavFormat.classifyFailure(null, "weird", null))
    }

    @Test fun failureSentencesSayWhatDidNotHappen() {
        assertTrue(NavFormat.failureSentence(RouteFailure.OFFLINE, null, "Home").startsWith("No connection"))
        assertTrue(NavFormat.failureSentence(RouteFailure.NO_ROUTE, null, "Home").contains("no drivable route to Home"))
        assertTrue(NavFormat.failureSentence(RouteFailure.OTHER, null, null).contains("unknown reason"))
    }

    @Test fun turnRotationFollowsTheModifier() {
        assertEquals(-90f, NavFormat.turnRotation("left"), 0f)
        assertEquals(90f, NavFormat.turnRotation("right"), 0f)
        assertEquals(180f, NavFormat.turnRotation("uturn"), 0f)
        assertEquals(0f, NavFormat.turnRotation(null), 0f)
        assertEquals(0f, NavFormat.turnRotation("roundabout-ish"), 0f)
    }

    @Test fun excludeStringRoundTripsToKinds() {
        assertEquals(setOf(AvoidKind.TOLLS, AvoidKind.HIGHWAYS), AvoidKind.fromExclude("toll,motorway,unpaved"))
        assertEquals(emptySet<AvoidKind>(), AvoidKind.fromExclude(null))
        assertNotNull(AvoidKind.entries.firstOrNull { it.spoken == "ferries" })
    }
}

class TrafficSummaryTest {
    @Test fun nothingToSayIsNullNotNoTraffic() {
        assertNull(TrafficSummary.of(600.0, null, null, null))
        assertNull(TrafficSummary.of(600.0, null, listOf(null, null), null))
    }

    @Test fun slowerThanUsualNamesTheDifference() {
        assertEquals("About 10 min slower than usual.", TrafficSummary.of(1200.0, 600.0, null, null))
        assertEquals("About 5 min faster than usual.", TrafficSummary.of(300.0, 600.0, null, null))
        assertEquals("About as long as usual.", TrafficSummary.of(630.0, 600.0, null, null))
    }

    @Test fun heavySegmentsAreMeasuredWhenDistancesAreKnown() {
        val s = TrafficSummary.of(600.0, null, listOf(10, 70, 90, 20), listOf(1000.0, 1609.344, 1609.344, 500.0))
        assertEquals("Heavy traffic for about 2.0 mi, severe in places.", s)
    }

    @Test fun cleanRoadIsSaidOnlyWhenCongestionWasReported() {
        val s = TrafficSummary.of(600.0, null, listOf(5, 10), listOf(1.0, 1.0))
        assertEquals("No heavy traffic reported on the route.", s)
    }

    @Test fun heavyWithNoDistancesStillSaysSo() {
        assertEquals("Heavy traffic reported on part of the route.", TrafficSummary.of(600.0, null, listOf(75), null))
    }
}
