package com.kevin.legion.navigation.voice

import com.kevin.legion.navigation.FakeNavSdk
import com.kevin.legion.navigation.FakeTokens
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.audit.NavAudit
import com.kevin.legion.navigation.audit.fields
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.DestinationResolver
import com.kevin.legion.navigation.resolve.DestinationSource
import com.kevin.legion.navigation.resolve.LookupContext
import com.kevin.legion.navigation.resolve.PlaceKind
import com.kevin.legion.navigation.resolve.PlaceSearch
import com.kevin.legion.navigation.resolve.SearchAnswer
import com.kevin.legion.navigation.resolve.SearchQuery
import com.kevin.legion.navigation.resolve.SourceAnswer
import com.kevin.legion.navigation.resolve.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice tools' `resolve` and `pick` lines: an ask, then the user's yes, tied to one search id. */
class NavVoiceToolsAuditTest {
    private val lines = mutableListOf<String>()
    private val audit = NavAudit(sink = { lines += it }, nowMs = { 1_800_000_000_000L })

    private val shells = listOf(
        Candidate("Shell", "1 Main St", 29.71, -95.41, 2_000.0, SourceKind.SEARCH, "gas station", PlaceKind.POI),
        Candidate(
            "Shell Express", "9 Kirby Dr", 29.75, -95.45, 6_000.0, SourceKind.SEARCH, "gas station", PlaceKind.POI,
        ),
    )

    private val nothingLocal = object : DestinationSource {
        override val kind = SourceKind.SAVED_PLACE

        override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer = SourceAnswer.NoMatch
    }

    private val search = object : PlaceSearch {
        override suspend fun search(query: SearchQuery): SearchAnswer =
            if (query.text.contains("shell")) SearchAnswer.Hits(shells) else SearchAnswer.NoMatch
    }

    private val sdk = FakeNavSdk().apply { autoReply = FakeNavSdk::honest }
    private val controller = MapboxNavController(
        tokens = FakeTokens(),
        sdkFactory = { sdk },
        fix = { GeoPoint(29.6, -95.3) },
        nowMs = { 1_800_000_000_000L },
        audit = audit,
    )
    private val tools = NavVoiceTools(
        controller = controller,
        resolver = DestinationResolver(listOf(nothingLocal), search, audit),
        fix = { GeoPoint(29.6, -95.3) },
        screen = { true },
        nowMs = { 0L },
        audit = audit,
    )

    private fun lineOf(event: String) = lines.filter { it.startsWith("$event ") }

    @Test fun anAskThenAYesIsOneSearchAndOnePickAtTheChosenRank() = runBlocking {
        assertFalse(tools.navigate("shell station").success)
        val search = fields(lineOf("search").single())
        assertEquals("voice", search["origin"])
        assertEquals("ambiguous", search["verdict"])
        val ask = fields(lineOf("resolve").single())
        assertEquals("ask", ask["verdict"])
        assertEquals(search["id"], ask["search"])
        assertEquals("\"several_plausible\"", ask["reason"])
        assertTrue("the read-back names stay out", lines.none { it.contains("Shell Express") })

        assertTrue(tools.navigate("shell station", choice = 2).success)
        assertEquals("the yes ran no second search", 1, lineOf("search").size)
        val pick = fields(lineOf("pick").single())
        assertEquals(search["id"], pick["search"])
        assertEquals("2", pick["rank"])
        assertEquals("voice", pick["how"])
        assertEquals("choice_ok", fields(lineOf("resolve").last())["verdict"])
        assertEquals("search", fields(lineOf("route_request").single())["to_src"])
        assertEquals(1, lineOf("trip_start").size)
    }

    @Test fun nothingFoundIsANotFoundResolveWithTheReason() = runBlocking {
        assertFalse(tools.navigate("zzyzx").success)
        val f = fields(lineOf("resolve").single())
        assertEquals("not_found", f["verdict"])
        assertEquals("navigate", f["tool"])
        assertEquals("destination", f["slot"])
        assertTrue(f.getValue("reason"), f.getValue("reason").contains("zzyzx"))
    }

    @Test fun aChoiceOutOfRangeSaysSo() = runBlocking {
        tools.navigate("shell station")
        assertFalse(tools.navigate("shell station", choice = 5).success)
        assertEquals("choice_out_of_range", fields(lineOf("resolve").last())["verdict"])
        assertTrue(lineOf("pick").isEmpty())
    }
}
