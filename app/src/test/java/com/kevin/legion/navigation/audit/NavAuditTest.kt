package com.kevin.legion.navigation.audit

import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavRouteInfo
import com.kevin.legion.navigation.RerouteStatus
import com.kevin.legion.navigation.RouteFailure
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.PlaceKind
import com.kevin.legion.navigation.resolve.SourceKind
import com.kevin.legion.service.LiveAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `key=value` tokens of one NAV_AUDIT line (the quoted free text last is taken whole). */
internal fun fields(line: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    Regex("(\\w+)=(\"(?:[^\"\\\\]|\\\\.)*\"|\\S+)").findAll(line).forEach { out[it.groupValues[1]] = it.groupValues[2] }
    return out
}

class NavAuditTest {
    private val lines = mutableListOf<String>()
    private var now = 1_800_000_000_000L
    private var uploads = 0
    private val audit = NavAudit(sink = { lines += it }, nowMs = { now }, onTripEnd = { uploads++ })

    private val fix = GeoPoint(29.71234, -95.40876)
    private val dest =
        NavDestination("Starbucks", 29.73456, -95.41234, "Coffee · 1 Main St", "search", "dXJuOm1ieHBvaTpmYWtl")

    private fun route(
        durationS: Double = 1200.0,
        distanceM: Double = 15_000.0,
        profile: String? = "mapbox/driving-traffic",
    ) =
        NavRouteInfo(
            id = "r#0",
            durationS = durationS,
            distanceM = distanceM,
            typicalDurationS = 1100.0,
            via = "I-69 S",
            hasTolls = false,
            waypoints = listOf(dest.point),
            excluded = emptySet(),
            trafficSummary = null,
            profile = profile,
        )

    private fun candidate(
        name: String,
        source: SourceKind,
        distanceM: Double?,
        kind: PlaceKind = PlaceKind.POI,
        mapboxId: String? = null,
    ) = Candidate(name, "12 Secret Rd, Houston", 29.7, -95.4, distanceM, source, "coffee", kind, mapboxId)

    @Test fun aSearchLineCarriesTheSourcesTheTopFiveAndTheQueryLast() {
        audit.search(
            id = "s1",
            origin = SearchOrigin.VOICE,
            alongRoute = false,
            verdict = SearchVerdict.AMBIGUOUS,
            source = SourceKind.SEARCH,
            tried = listOf(
                SourceTry(SourceKind.SAVED_PLACE, 2, 0, SourceOutcome.NO_MATCH),
                SourceTry(SourceKind.CALENDAR, 0, 0, SourceOutcome.UNREADABLE),
                SourceTry(SourceKind.SEARCH, 812, 6, SourceOutcome.HITS),
            ),
            top = (1..6).map { candidate("Starbucks $it", SourceKind.SEARCH, 1234.0 * it, mapboxId = "mbx$it") },
            totalMs = 820,
            query = "the Starbucks near work",
        )
        val line = lines.single()
        assertTrue(line, line.startsWith("search id=s1 origin=voice along=false verdict=ambiguous source=search"))
        val f = fields(line)
        assertEquals("saved_place:2:0:no_match,calendar:0:0:unreadable,search:812:6:hits", f["tried"])
        assertEquals("820", f["ms"])
        assertTrue("the free text is last", line.endsWith("query=\"the Starbucks near work\""))
        val top = f.getValue("top")
        assertEquals("five entries, not six", 5, Regex("\"r\":").findAll(top).count())
        assertTrue(
            top,
            top.startsWith(
                "[{\"r\":1,\"src\":\"search\",\"id\":\"mbx1\",\"kind\":\"poi\",\"cat\":\"coffee\",\"m\":1230}",
            ),
        )
    }

    @Test fun aMapboxHitIsNeverNamedAndNoSourceLogsItsAddress() {
        val top = NavAudit.topJson(
            listOf(
                candidate("Blue Bottle Coffee", SourceKind.SEARCH, 500.0, mapboxId = "dXJuOm1ieHBvaTpibHVl"),
                // A contact's address that Mapbox geocoded: its id would point at the contact's address (D9).
                candidate("Mia's mom", SourceKind.CONTACTS, 9000.0, PlaceKind.OTHER, mapboxId = "dXJuOmFkZHJlc3M"),
                candidate("work", SourceKind.SAVED_PLACE, null, PlaceKind.OTHER),
                candidate("No Id Diner", SourceKind.SEARCH, 700.0),
            ),
        )
        assertFalse("a Mapbox result name is never stored (ToS 2.7.2)", top.contains("Blue Bottle"))
        assertTrue(
            "a search hit carries its mapbox_id (ToS 2.7.6, D1)",
            top.contains("\"id\":\"dXJuOm1ieHBvaTpibHVl\""),
        )
        assertFalse("a first-party hit never carries an id", top.contains("dXJuOmFkZHJlc3M"))
        assertEquals("only the search hit that had one", 1, Regex("\"id\":").findAll(top).count())
        assertFalse("address text from any source never logs", top.contains("Secret Rd"))
        assertTrue(top, top.contains("\"name\":\"Mia's\\u0020mom\""))
        assertTrue(top, top.contains("\"name\":\"work\""))
        assertTrue("unknown distance is null, not zero", top.contains("\"m\":null"))
        assertFalse("one whitespace-free token", top.contains(' '))
    }

    @Test fun aRouteRequestIsRoundedToAboutAHundredMetresAndSaysWhetherTrafficWasUsed() {
        audit.routeRequest(
            id = "r1", purpose = RoutePurpose.PREVIEW, result = "ok", failure = null, ms = 640,
            routes = listOf(route(), route(1300.0), route(1400.0)), router = "ONLINE",
            origin = fix, destination = dest, stops = 0, avoid = setOf(AvoidKind.TOLLS), message = null,
        )
        val f = fields(lines.single())
        assertEquals("29.712,-95.409", f["from"])
        assertEquals("a search destination's point is a Mapbox geocode, never logged (D2)", "none", f["to"])
        assertEquals("search", f["to_src"])
        assertEquals("dXJuOm1ieHBvaTpmYWtl", f["to_id"])
        assertEquals("true", f["traffic"])
        assertEquals("mapbox/driving-traffic", f["profile"])
        assertEquals("3", f["routes"])
        assertEquals("2", f["alternatives"])
        assertEquals("1200", f["eta_s"])
        assertEquals("15000", f["distance_m"])
        assertEquals("online", f["router"])
        assertEquals("toll", f["avoid"])
        assertEquals("none", f["trip"])
        assertFalse("never the destination's name or address", lines.single().contains("Starbucks"))
    }

    @Test fun onlyASavedPlaceDestinationLogsItsPoint() {
        fun to(source: String?, id: String? = "dXJuOmFkZHJlc3M"): Map<String, String> {
            lines.clear()
            audit.routeRequest(
                id = "r3", purpose = RoutePurpose.PREVIEW, result = "ok", failure = null, ms = 500,
                routes = listOf(route()), router = "ONLINE", origin = fix,
                destination = NavDestination("x", 29.73456, -95.41234, null, source, id),
                stops = 0, avoid = emptySet(), message = null,
            )
            return fields(lines.single())
        }
        val saved = to("saved_place", id = null)
        assertEquals("the household's own GPS fix, rounded (D2)", "29.735,-95.412", saved["to"])
        assertEquals("none", saved["to_id"])
        for (geocoded in listOf("calendar", "suggestions", "contacts", null)) {
            val f = to(geocoded)
            assertEquals("$geocoded: a Mapbox geocode of address text is never logged", "none", f["to"])
            assertEquals("$geocoded: an id only on a search destination", "none", f["to_id"])
            assertTrue("crow distance stays", f.getValue("crow_m").toInt() > 0)
        }
    }

    @Test fun aFailedRouteRequestScrubsAMapboxTokenOutOfItsMessage() {
        audit.routeRequest(
            id = "r2", purpose = RoutePurpose.CHANGE, result = "failed", failure = RouteFailure.OFFLINE, ms = 30_000,
            routes = emptyList(), router = null, origin = fix, destination = dest, stops = 1, avoid = emptySet(),
            message = "GET https://api.mapbox.com/directions/v5?access_token=pk.eyJ1Ijoia2V2aW4ifQ.abc123 failed " +
                "(token sk.eyJzZWNyZXQifQ.zzz)",
        )
        val line = lines.single()
        assertFalse(line, line.contains("eyJ"))
        val f = fields(line)
        assertEquals("offline", f["fail"])
        assertEquals("none", f["eta_s"])
        assertEquals("0", f["alternatives"])
        assertEquals("unknown", f["traffic"])
    }

    @Test fun liveAuditScrubsMapboxTokensToo() {
        assertEquals("token [key] here", LiveAudit.scrub("token pk.eyJ1IjoiYSJ9.Zz-9_x here"))
    }

    @Test fun aTripReportsRerouteReasonsCueCountsAndArrivalAgainstTheFirstEta() {
        audit.tripStarted("r#0", route(durationS = 1200.0), routeIndex = 1, stops = 0, avoid = emptySet())
        val start = fields(lines.last())
        assertTrue(lines.last().startsWith("trip_start trip=t"))
        assertEquals("1", start["route_index"])
        assertEquals("1200", start["eta_s"])
        val trip = start.getValue("trip")

        now += 300_000
        audit.offRoute(true)
        audit.offRoute(true) // no change, no line
        audit.reroute(RerouteStatus.FETCHING, null)
        now += 900
        audit.reroute(RerouteStatus.FETCHED, "ONLINE")
        audit.offRoute(false)
        audit.routesUpdated("reroute", route(durationS = 1000.0), 2)
        audit.reroute(RerouteStatus.IDLE, null) // the screen's idle is not an audit event
        repeat(3) { audit.cue(CueOutcome.SPOKEN) }
        audit.cue(CueOutcome.SUPERSEDED)
        audit.cue(CueOutcome.MUTED)

        now += 1_080_000 // 1380 s after the start, against a first ETA of 1200 s
        audit.arrived()

        val events = lines.map { it.substringBefore(' ') }
        assertEquals(
            listOf("trip_start", "off_route", "reroute", "reroute", "off_route", "route_update", "arrival", "trip_end"),
            events,
        )
        assertEquals("off_route", fields(lines[2])["reason"])
        assertEquals("900", fields(lines[3])["ms"])
        assertEquals("fetched", fields(lines[3])["state"])
        val arrival = fields(lines[6])
        assertEquals(trip, arrival["trip"])
        assertEquals("1380", arrival["elapsed_s"])
        assertEquals("180", arrival["error_s"])
        assertEquals("15.0", arrival["error_pct"])
        val end = fields(lines[7])
        assertEquals("arrived", end["reason"])
        assertEquals("1", end["reroutes"])
        assertEquals("1", end["off_routes"])
        assertEquals("3", end["cues_spoken"])
        assertEquals("2", end["cues_dropped"])
        assertEquals("muted:1,superseded:1", end["cue_drops"])
        assertEquals("a finished trip asks for an upload", 1, uploads)
    }

    @Test fun aTripEndsOnceAndNothingIsLoggedForATripThatIsNotRunning() {
        audit.offRoute(true)
        audit.cue(CueOutcome.SPOKEN)
        audit.tripEnded(TripEnd.CANCELLED, 100.0)
        assertTrue("no trip, no line", lines.isEmpty())
        audit.tripStarted(null, route(), 0, 1, setOf(AvoidKind.HIGHWAYS))
        audit.tripEnded(TripEnd.CANCELLED, 4321.4)
        audit.tripEnded(TripEnd.TOKEN, null)
        audit.arrived()
        assertEquals(2, lines.size)
        val end = fields(lines[1])
        assertEquals("cancelled", end["reason"])
        assertEquals("4321", end["distance_left_m"])
        assertEquals("none", end["cue_drops"])
        assertEquals(1, uploads)
    }

    @Test fun idsAreDistinctWithinOneMillisecond() {
        val ids = (1..5).map { audit.newId("s") }
        assertEquals(5, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("s") && !it.contains(' ') })
    }

    @Test fun aPickAndAResolveNeverCarryTheReadBackSentence() {
        audit.pick("s1", 2, "voice", SourceKind.SEARCH)
        audit.resolve(
            "navigate", "destination", ResolveVerdict.ASK, "s1", 3, null, SourceKind.SEARCH, "several_plausible",
        )
        assertEquals("pick search=s1 rank=2 how=voice source=search", lines[0])
        val f = fields(lines[1])
        assertEquals("ask", f["verdict"])
        assertEquals("none", f["choice"])
        assertTrue(lines[1].endsWith("reason=\"several_plausible\""))
    }
}
