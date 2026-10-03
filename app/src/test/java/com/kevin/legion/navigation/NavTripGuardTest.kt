package com.kevin.legion.navigation

import com.kevin.legion.data.local.TaggedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTripGuardTest {
    @Test fun sessionRunsOnlyAfterRoutesArrive() {
        val g = NavTripGuard()
        assertFalse(g.sessionShouldRun)
        assertTrue(g.requestStarted())
        assertFalse("requesting is not a trip", g.sessionShouldRun)
        assertTrue(g.routesReady(1))
        assertTrue(g.sessionShouldRun)
        assertEquals(NavPhase.GUIDING, g.phase)
    }

    @Test fun emptyRouteAnswerNeverStartsASession() {
        val g = NavTripGuard()
        g.requestStarted()
        assertFalse(g.routesReady(0))
        assertEquals(NavPhase.FAILED, g.phase)
        assertFalse(g.sessionShouldRun)
    }

    @Test fun routesWithNoRequestInFlightAreRefused() {
        val g = NavTripGuard()
        assertFalse("no request, so no session", g.routesReady(2))
        assertFalse(g.sessionShouldRun)
    }

    @Test fun stopWhileRequestingBlocksTheLateRouteResponse() {
        val g = NavTripGuard()
        g.requestStarted()
        assertFalse("no session was running to stop", g.stopRequested())
        assertEquals(NavPhase.IDLE, g.phase)
        assertFalse("a late response must not start a trip", g.routesReady(1))
        assertFalse(g.sessionShouldRun)
    }

    @Test fun stopWhileGuidingSaysStopTheSession() {
        val g = NavTripGuard()
        g.requestStarted()
        g.routesReady(1)
        assertTrue(g.stopRequested())
        assertFalse(g.sessionShouldRun)
        assertEquals(NavPhase.IDLE, g.phase)
    }

    @Test fun arrivalStopsTheSessionAndKeepsTheVerdict() {
        val g = NavTripGuard()
        g.requestStarted()
        g.routesReady(1)
        assertTrue(g.arrived())
        assertEquals(NavPhase.ARRIVED, g.phase)
        assertFalse(g.sessionShouldRun)
        assertFalse("second arrival signal is a no-op", g.arrived())
    }

    @Test fun screenLeavingWhileGuidingStopsTheSession() {
        val g = NavTripGuard()
        g.requestStarted()
        g.routesReady(1)
        assertTrue(g.screenLeft())
        assertFalse(g.sessionShouldRun)
    }

    @Test fun aSecondRequestWhileOneRunsIsRefused() {
        val g = NavTripGuard()
        assertTrue(g.requestStarted())
        assertFalse(g.requestStarted())
        g.routesReady(1)
        assertFalse(g.requestStarted())
    }

    @Test fun canRequestAgainAfterFailureOrArrival() {
        val g = NavTripGuard()
        g.requestStarted()
        g.requestFailed()
        assertEquals(NavPhase.FAILED, g.phase)
        assertTrue(g.requestStarted())
        g.routesReady(1)
        g.arrived()
        assertTrue(g.requestStarted())
    }
}

class NavFormatTest {
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
}

class NavDestinationsTest {
    private fun place(label: String, deleted: Boolean = false) =
        TaggedPlace(label = label, latitude = 1.0, longitude = 2.0, timestamp = 0L, deleted = deleted)

    @Test fun homeWinsCaseInsensitively() {
        val d = NavDestinations.forSpike(listOf(place("Work"), place("HOME")))
        assertEquals("HOME", d.name)
        assertEquals(1.0, d.latitude, 0.0)
        assertEquals(2.0, d.longitude, 0.0)
    }

    @Test fun noHomeFallsBackToHouston() {
        assertSame(NavDestinations.HOUSTON_TEST, NavDestinations.forSpike(listOf(place("Work"))))
        assertSame(NavDestinations.HOUSTON_TEST, NavDestinations.forSpike(emptyList()))
    }

    @Test fun deletedHomeIsIgnored() {
        assertSame(NavDestinations.HOUSTON_TEST, NavDestinations.forSpike(listOf(place("home", deleted = true))))
    }
}
