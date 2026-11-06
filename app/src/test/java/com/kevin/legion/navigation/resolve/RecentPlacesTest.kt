package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryIo(var text: String? = null) : RecentPlacesIo {
    override fun read(): String? = text

    override fun write(text: String?) {
        this.text = text
    }
}

/** Nav-quality ticket 13: recent and frequent destinations, kept by the arrival fix (decision D4). */
class RecentPlacesTest {
    private val day = 86_400_000L
    private var now = 1_800_000_000_000L
    private val io = MemoryIo()
    private val recents = RecentPlaces(io, nowMs = { now })

    // Fictional places throughout.
    private val cafe = GeoPoint(29.70000, -95.40000)
    private val mapboxPoint = GeoPoint(29.71111, -95.41111)

    private fun searched(asked: String?, source: String? = "search") =
        NavDestination(
            "Blue Door Cafe", mapboxPoint.latitude, mapboxPoint.longitude, "Cafe · 1 Fake St", source, "id1", asked,
        )

    @Test fun anArrivalAtASearchedPlaceKeepsTheWordsAndTheArrivalFixNeverTheMapboxResult() {
        assertTrue(recents.arrived(searched("blue door cafe"), cafe))
        val row = recents.all().single()
        assertEquals("blue door cafe", row.phrase)
        assertEquals(cafe, row.point)
        assertEquals(1, row.count)
        val stored = io.text.orEmpty()
        assertFalse("never the Mapbox name (ToS 2.7.2)", stored.contains("Blue Door"))
        assertFalse("never the Mapbox address", stored.contains("Fake St"))
        assertFalse("never the Mapbox id", stored.contains("id1"))
        assertFalse("never the Mapbox point", stored.contains("29.711110"))
    }

    @Test fun onlyASearchedArrivalWithWordsAndAFixIsKept() {
        assertFalse("a saved place is already known", recents.arrived(searched("gym", "saved_place"), cafe))
        assertFalse(
            "a calendar place changes every time",
            recents.arrived(searched("next appointment", "calendar"), cafe),
        )
        assertFalse("a contact's address is theirs (D9)", recents.arrived(searched("aunt example", "contacts"), cafe))
        assertFalse("no words, nothing to match on", recents.arrived(searched(null), cafe))
        assertFalse("no fix, nothing first-party to keep", recents.arrived(searched("blue door cafe"), null))
        assertFalse("nearest means from wherever you are", recents.arrived(searched("nearest gas station"), cafe))
        assertFalse(recents.arrived(null, cafe))
        assertTrue(recents.all().isEmpty())
    }

    @Test fun aRepeatArrivalNearbyCountsAndMovesTheFixAFarOneStartsAgain() {
        recents.record("blue door cafe", cafe)
        now += day
        val close = GeoPoint(29.70090, -95.40000) // about 100 m
        recents.record("Blue Door Cafe!", close)
        assertEquals(2, recents.all().single().count)
        assertEquals(close, recents.all().single().point)
        val elsewhere = GeoPoint(29.80000, -95.40000) // about 11 km
        recents.record("blue door cafe", elsewhere)
        val row = recents.all().single()
        assertEquals(1, row.count)
        assertEquals(elsewhere, row.point)
    }

    @Test fun frequentAndRecentOutranksOldAndOnce() {
        recents.record("old haunt", cafe)
        now += 90 * day
        repeat(3) { recents.record("weekly market", GeoPoint(29.6, -95.3)) }
        recents.record("one off", GeoPoint(29.5, -95.2))
        assertEquals(listOf("weekly market", "one off", "old haunt"), recents.all().map { it.phrase })
    }

    @Test fun theStoreIsCappedAndForgetClearsItAll() {
        repeat(RecentPlaces.MAX_ROWS + 5) { recents.record("place $it", GeoPoint(29.0 + it * 0.01, -95.0)) }
        assertEquals(RecentPlaces.MAX_ROWS, recents.all().size)
        recents.forget()
        assertTrue(recents.all().isEmpty())
        assertEquals(null, io.text)
    }

    @Test fun aDamagedStoreReadsAsNoneNotACrash() {
        io.text = "garbage\nblue door cafe\tnot-a-number\t1\t1\t1\n\t\t\t\t"
        assertTrue(recents.all().isEmpty())
    }

    @Test fun theSourceMatchesLikeSavedPlaces() = runBlocking {
        recents.record("blue door cafe", cafe)
        recents.record("blue door bakery", GeoPoint(29.6, -95.3))
        val source = RecentSource(recents)
        val ctx = LookupContext(GeoPoint(29.69, -95.4))
        val exact = source.lookup("take me to the blue door cafe", ctx) as SourceAnswer.Hits
        assertFalse(exact.ambiguous)
        assertEquals("blue door cafe", exact.candidates.single().name)
        assertEquals(SourceKind.RECENT, exact.candidates.single().source)
        assertEquals("first-party point", cafe.latitude, exact.candidates.single().latitude, 0.0)
        val partial = source.lookup("blue door", ctx) as SourceAnswer.Hits
        assertTrue("two contain the phrase: ask", partial.ambiguous)
        assertEquals(SourceAnswer.NoMatch, source.lookup("zzyzx", ctx))
    }

    @Test fun aRecentAnswersBeforeSearchAndASavedPlaceStillWins() = runBlocking {
        recents.record("blue door cafe", cafe)
        recents.record("gym", GeoPoint(29.6, -95.3))
        var searched = 0
        val resolver = DestinationResolver(
            sources = listOf(
                SavedPlaceSource(
                    object : SavedPlacesReader {
                        override suspend fun read(): PlacesRead =
                            PlacesRead.Places(listOf(SavedPlaceRow("gym", 29.9, -95.9)))
                    },
                ),
                RecentSource(recents),
            ),
            placeSearch = object : PlaceSearch {
                override suspend fun search(query: SearchQuery): SearchAnswer {
                    searched++
                    return SearchAnswer.NoMatch
                }
            },
        )
        val r = resolver.resolve("blue door cafe", LookupContext(null)) as Resolution.Resolved
        assertEquals(SourceKind.RECENT, r.source)
        assertEquals("recent", r.destination.source)
        assertEquals("blue door cafe", r.destination.asked)
        assertEquals(0, searched)
        val gym = resolver.resolve("gym", LookupContext(null)) as Resolution.Resolved
        assertEquals("a saved place wins over a recent of the same name", SourceKind.SAVED_PLACE, gym.source)
        assertEquals(29.9, gym.destination.latitude, 0.0)
    }
}
