package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.audit.NavAudit
import com.kevin.legion.navigation.audit.SearchOrigin
import com.kevin.legion.navigation.audit.fields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resolver's NAV_AUDIT `search` line: which sources ran, how long each took, and the verdict. */
class ResolverAuditTest {
    private val fix = GeoPoint(29.7, -95.4)
    private val lines = mutableListOf<String>()
    private val audit = NavAudit(sink = { lines += it }, nowMs = { 1_800_000_000_000L })

    /** Each read of the clock moves it on by [step] ms, so every source "takes" a known time. */
    private var clock = 0L
    private var step = 0L

    private var places: PlacesRead = PlacesRead.Places(listOf(SavedPlaceRow("work", 29.9, -95.6)))
    private var searchReply: (SearchQuery) -> SearchAnswer = { SearchAnswer.NoMatch }

    private fun resolver() = DestinationResolver(
        sources = listOf(
            SavedPlaceSource(
                object : SavedPlacesReader {
                    override suspend fun read(): PlacesRead = places
                },
            ),
        ),
        placeSearch = object : PlaceSearch {
            override suspend fun search(query: SearchQuery): SearchAnswer {
                clock += 700 // Mapbox is slow
                return searchReply(query)
            }
        },
        audit = audit,
        clockMs = { clock.also { clock += step } },
    )

    private fun hit(name: String, lat: Double) =
        Candidate(name, "1 Main St", lat, -95.41, null, SourceKind.SEARCH, "coffee", PlaceKind.POI)

    @Test fun aSavedPlaceHitLogsOneSourceAndTheLabel() = runBlocking {
        val r = resolver().resolve("take me to work", LookupContext(fix), SearchOrigin.VOICE) as Resolution.Resolved
        val line = lines.single()
        val f = fields(line)
        assertEquals("search", line.substringBefore(' '))
        assertEquals(r.searchId, f["id"])
        assertEquals("voice", f["origin"])
        assertEquals("resolved", f["verdict"])
        assertEquals("saved_place", f["source"])
        val tried = f.getValue("tried")
        assertTrue("only the source that ran: $tried", tried.matches(Regex("saved_place:\\d+:1:hits")))
        assertTrue(f.getValue("top").contains("\"name\":\"work\""))
        assertEquals("saved_place", r.destination.source)
    }

    @Test fun aMapboxAnswerIsTimedPerSourceAndItsNamesStayOutOfTheLog() = runBlocking {
        step = 1
        searchReply = { SearchAnswer.Hits(listOf(29.71, 29.72, 29.73).map { hit("Blue Bottle", it) }) }
        val r = resolver().resolve("blue bottle", LookupContext(fix), SearchOrigin.TYPED) as Resolution.Resolved
        val f = fields(lines.single())
        val tried = f.getValue("tried").split(',')
        assertEquals(2, tried.size)
        assertTrue(tried[0], tried[0].startsWith("saved_place:1:0:no_match"))
        assertTrue("search includes Mapbox's 700 ms: ${tried[1]}", tried[1].startsWith("search:701:3:hits"))
        assertEquals("typed", f["origin"])
        assertEquals("3", f["results"])
        val head = lines.single().substringBefore(" query=")
        assertFalse(head, head.contains("Blue Bottle"))
        assertEquals("search", r.destination.source)
    }

    @Test fun nothingFoundIsAZeroResultLineWithTheQuery() = runBlocking {
        val r = resolver().resolve("zzyzx road", LookupContext(fix))
        assertTrue(r is Resolution.NotFound)
        val f = fields(lines.single())
        assertEquals("not_found", f["verdict"])
        assertEquals("0", f["results"])
        assertEquals("none", f["source"])
        assertEquals("[]", f["top"])
        assertEquals("unknown", f["origin"])
        assertEquals("\"zzyzx road\"", f["query"])
    }

    @Test fun anUnreadableSourceIsLoggedAsUnreadableNotEmpty() = runBlocking {
        places = PlacesRead.Unreadable("disk")
        resolver().resolve("nowhere", LookupContext(fix))
        val tried = fields(lines.single()).getValue("tried")
        assertTrue(tried, tried.startsWith("saved_place:0:0:unreadable,search:"))
    }

    @Test fun aBlankPhraseIsAnEmptySearch() = runBlocking {
        resolver().resolve("   ", LookupContext(fix))
        val f = fields(lines.single())
        assertEquals("empty", f["verdict"])
        assertEquals("none", f["tried"])
    }
}
