package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-10-09: *"on the day itself, i say hey navigate to that event in austin > it does
 * that."* The suggestion source through the real [DestinationResolver], with a fake search that
 * geocodes any text to a point and records what it was asked.
 */
class SuggestionSourceTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-10-10T09:00")

    private val searched = mutableListOf<String>()
    private val search = object : PlaceSearch {
        override suspend fun search(query: SearchQuery): SearchAnswer {
            searched += query.text
            val hit = Candidate(query.text, query.text, 30.0, -97.0, null, SourceKind.SEARCH)
            return SearchAnswer.Hits(listOf(hit))
        }
    }

    private fun row(
        title: String,
        city: String?,
        start: String = "2026-10-10T19:00",
        address: String? = "1 Main St",
        venue: String? = "The Venue",
        structured: Boolean = true,
        location: String? = null,
    ) = SuggestionRow(title, at(start), false, city, venue, address, location, structured)

    private fun resolve(text: String, vararg rows: SuggestionRow): Resolution = runBlocking {
        DestinationResolver(
            listOf(SuggestionSource({ SuggestionsRead.Rows(rows.toList()) }, nowMs = { now }, zone = { zone })),
            search,
        ).resolve(text, LookupContext(GeoPoint(28.8, -97.0)))
    }

    @Test fun `a city-qualified phrase goes to that city's suggestion`() {
        val r = resolve(
            "navigate to that event in Austin",
            row("Pumpkin Festival", "Austin", address = "2100 Barton Springs Rd"),
            row("Bach Festival", "Victoria"),
        ) as Resolution.Resolved
        assertEquals(SourceKind.SUGGESTIONS, r.source)
        assertEquals("Pumpkin Festival", r.destination.name)
        assertFalse(r.ambiguous)
        assertEquals(listOf("2100 Barton Springs Rd, Austin"), searched)
    }

    @Test fun `a title phrase matches without a city`() {
        val rows = arrayOf(row("Pumpkin Festival", "Austin"), row("Bach Festival", "Victoria"))
        val r = resolve("take me to the pumpkin festival", *rows)
            as Resolution.Resolved
        assertEquals("Pumpkin Festival", r.destination.name)
        assertFalse(r.ambiguous)
    }

    @Test fun `today's match is preferred over a later one`() {
        val r = resolve(
            "navigate to the farmers market",
            row("Farmers Market", "Houston", start = "2026-10-17T08:00", address = "9 Later St"),
            row("Farmers Market", "Houston", start = "2026-10-10T08:00", address = "1 Today St"),
        ) as Resolution.Resolved
        assertFalse(r.ambiguous)
        assertEquals(listOf("1 Today St, Houston"), searched)
    }

    @Test fun `several matches the same day ask which one`() {
        val r = resolve(
            "navigate to that event in Austin",
            row("Pumpkin Festival", "Austin", address = "1 A St"),
            row("Film Night", "Austin", address = "2 B St"),
        ) as Resolution.Resolved
        assertTrue(r.ambiguous)
        assertTrue(r.sentence().contains("Is that the one?"))
    }

    @Test fun `several matches where only one has an address still ask`() {
        val r = resolve(
            "navigate to that event in Austin",
            row("Pumpkin Festival", "Austin", address = "1 A St"),
            row("Film Night", "Austin", address = null),
        ) as Resolution.Resolved
        assertTrue(r.ambiguous)
    }

    @Test fun `no street address says so and offers the venue as a search`() {
        val noAddress = row("Pumpkin Festival", "Austin", address = null, venue = "Zilker Park")
        val r = resolve("navigate to the pumpkin festival", noAddress) as Resolution.NotFound
        assertTrue(r.message.contains("no street address"))
        assertTrue(r.message.contains("navigate to Zilker Park, Austin"))
        assertTrue("nothing was guessed", searched.isEmpty())
    }

    @Test fun `an unstructured suggestion falls back to its location text`() {
        val loose = row(
            "Bach Festival", null, address = null, structured = false, location = "Hall, 5 Elm St, Victoria TX",
        )
        resolve("navigate to the bach festival", loose)
        assertEquals(listOf("Hall, 5 Elm St, Victoria TX"), searched)
    }

    @Test fun `a phrase that names nothing on the list falls through`() {
        val r = resolve("navigate to starbucks", row("Pumpkin Festival", "Austin"))
        // The fake search geocodes anything, so a fall-through lands on Mapbox search, not a suggestion.
        assertEquals(SourceKind.SEARCH, (r as Resolution.Resolved).source)
    }

    @Test fun `an unreadable store is said, not treated as empty`() = runBlocking {
        val answer = SuggestionSource({ SuggestionsRead.Unreadable("disk on fire") }, { now }, { zone })
            .lookup("pumpkin festival", LookupContext(null))
        assertEquals(SourceAnswer.Unreadable("disk on fire"), answer)
    }

    @Test fun `an all-day suggestion's date is its own, not the UTC instant's local date`() {
        val allDay = SuggestionRow(
            "Fair", LocalDateTime.parse("2026-10-10T00:00").toInstant(ZoneOffset.UTC).toEpochMilli(), true,
            "Austin", "Grounds", "3 C St", null, true,
        )
        val later = row("Fair", "Austin", start = "2026-10-11T10:00", address = "4 D St")
        val r = resolve("navigate to the fair", allDay, later) as Resolution.Resolved
        assertFalse(r.ambiguous)
        assertEquals(listOf("3 C St, Austin"), searched)
    }
}
