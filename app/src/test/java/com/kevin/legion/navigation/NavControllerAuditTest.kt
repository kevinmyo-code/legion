package com.kevin.legion.navigation

import com.kevin.legion.navigation.audit.NavAudit
import com.kevin.legion.navigation.audit.fields
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller's NAV_AUDIT lines, against the scripted [FakeNavSdk] and a clock the test moves. */
class NavControllerAuditTest {
    private val home = NavDestination("Home", 29.7, -95.4, source = "saved_place")
    private val bank = NavDestination("Bank", 29.72, -95.41, source = "search")
    private val lines = mutableListOf<String>()
    private var now = 1_800_000_000_000L
    private val audit = NavAudit(sink = { lines += it }, nowMs = { now })
    private val sdk = FakeNavSdk().apply { autoReply = FakeNavSdk::honest }
    private val tokens = FakeTokens()
    private val controller = MapboxNavController(
        tokens = tokens,
        sdkFactory = { sdk },
        fix = { GeoPoint(29.6, -95.3) },
        nowMs = { now },
        audit = audit,
    )

    private fun events() = lines.map { it.substringBefore(' ') }

    private fun last(event: String) = fields(lines.last { it.startsWith("$event ") })

    @Test fun aPreviewLogsItsRouteRequestAndNoTrip() = runBlocking {
        assertTrue(controller.preview(home).ok)
        assertEquals(listOf("route_request"), events())
        val f = last("route_request")
        assertEquals("preview", f["purpose"])
        assertEquals("ok", f["result"])
        assertEquals("2", f["routes"])
        assertEquals("1", f["alternatives"])
        assertEquals("1080", f["eta_s"])
        assertEquals("29.600,-95.300", f["from"])
        assertEquals("29.700,-95.400", f["to"])
        assertEquals("saved_place", f["to_src"])
        assertEquals("unknown", f["traffic"])
    }

    @Test fun aSlowRequestIsTimed() = runBlocking {
        sdk.autoReply = null
        val job = launch { controller.preview(home) }
        yield()
        now += 2_345
        sdk.deliver(FakeNavSdk.honest(sdk.requests.single()))
        job.join()
        assertEquals("2345", last("route_request")["ms"])
    }

    @Test fun anArrivalHandsTheDestinationAndThePhonesOwnFixToRecents() = runBlocking {
        // Nav-quality ticket 13: the recents store keeps the arrival fix, never the route's point.
        val arrivals = mutableListOf<Pair<NavDestination?, GeoPoint?>>()
        val c = MapboxNavController(
            tokens = tokens,
            sdkFactory = { sdk },
            fix = { GeoPoint(29.6, -95.3) },
            nowMs = { now },
            audit = audit,
            onArrived = { d, fix -> arrivals += d to fix },
        )
        assertTrue(c.navigate(bank).ok)
        c.onArrival()
        c.onArrival() // the progress backup firing too must not record twice
        assertEquals(1, arrivals.size)
        assertEquals("Bank", arrivals.single().first?.name)
        assertEquals(GeoPoint(29.6, -95.3), arrivals.single().second)
    }

    @Test fun aRecentPlaceLogsItsPointLikeASavedOne() = runBlocking {
        assertTrue(controller.preview(NavDestination("blue door cafe", 29.7, -95.4, source = "recent")).ok)
        assertEquals("29.700,-95.400", last("route_request")["to"])
    }

    @Test fun aFailedRequestSaysWhy() = runBlocking {
        sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.OFFLINE, "no network") }
        assertTrue(!controller.preview(home).ok)
        val f = last("route_request")
        assertEquals("failed", f["result"])
        assertEquals("offline", f["fail"])
        assertEquals("\"no network\"", f["message"])
    }

    @Test fun aTripRunsFromStartThroughAChangeToArrivalAgainstItsFirstEta() = runBlocking {
        assertTrue(controller.navigate(home).ok)
        val trip = last("trip_start").getValue("trip")
        assertEquals("1080", last("trip_start")["eta_s"])
        assertTrue(controller.addStop(bank).ok)
        val change = last("route_request")
        assertEquals("change", change["purpose"])
        assertEquals(trip, change["trip"])
        assertEquals("1", change["stops"])

        controller.onOffRoute(true)
        controller.onReroute(RerouteStatus.FETCHING, null)
        controller.onReroute(RerouteStatus.FETCHED, "ONLINE")
        controller.onRoutesChanged(sdk.activeRoutes(), "reroute")
        now += 1_200_000
        controller.onArrival()
        controller.onArrival() // the progress backup firing too must not double the lines

        assertEquals(
            listOf(
                "route_request", "trip_start", "route_request", "off_route", "reroute", "reroute", "route_update",
                "arrival", "trip_end",
            ),
            events(),
        )
        assertEquals("off_route", fields(lines[4])["reason"])
        assertEquals("reroute", last("route_update")["reason"])
        assertEquals("120", last("arrival")["error_s"])
        assertEquals("arrived", last("trip_end")["reason"])
        assertEquals("1", last("trip_end")["reroutes"])
    }

    @Test fun endingATripByHandIsCancelled() = runBlocking {
        assertTrue(controller.navigate(home).ok)
        controller.onProgress(NavProgressInfo(600.0, 7_000.0, null, null, 1, false))
        controller.end()
        val f = last("trip_end")
        assertEquals("cancelled", f["reason"])
        assertEquals("7000", f["distance_left_m"])
    }

    @Test fun clearingAPreviewIsNotATripEnd() = runBlocking {
        assertTrue(controller.preview(home).ok)
        controller.end()
        assertTrue(events().none { it == "trip_end" })
    }

    @Test fun aTokenChangeMidTripEndsItAsToken() = runBlocking {
        assertTrue(controller.navigate(home).ok)
        tokens.set("pk.other")
        controller.onTokenChanged()
        assertEquals("token", last("trip_end")["reason"])
    }

    @Test fun aStartTheSdkRefusesIsLoggedAsAFailedStart() = runBlocking {
        sdk.refuseToStart = true
        assertTrue(!controller.navigate(home).ok)
        assertEquals("failed", last("trip_start")["result"])
        assertEquals("none", last("trip_start")["trip"])
    }
}
