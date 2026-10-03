package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private class FakePlaces(var read: PlacesRead) : SavedPlacesReader {
    var reads = 0
    override suspend fun read(): PlacesRead = read.also { reads++ }
}

private class FakeEvents(
    var read: EventsRead,
    var devicePermission: Boolean = true,
) : UpcomingEvents {
    var reads = 0
    override suspend fun upcoming(nowMs: Long): EventsRead = read.also { reads++ }
    override fun canReadDeviceCalendar() = devicePermission
}

private class FakeContacts(var read: ContactsRead) : ContactsReader {
    var asked = emptyList<String>()
    override suspend fun read(names: List<String>): ContactsRead {
        asked = names
        return read
    }
}

private class FakeSearch(var reply: (SearchQuery) -> SearchAnswer = { SearchAnswer.NoMatch }) : PlaceSearch {
    val queries = mutableListOf<SearchQuery>()
    override suspend fun search(query: SearchQuery): SearchAnswer {
        queries += query
        return reply(query)
    }
}

class DestinationResolverTest {
    private val fix = GeoPoint(29.7, -95.4)
    private val ctx = LookupContext(fix)
    private val noon = 1_790_000_000_000L

    private fun hit(
        name: String,
        lat: Double = 29.71,
        lng: Double = -95.41,
        detail: String? = "1 Main St",
        distanceM: Double? = null,
        category: String? = null,
        kind: PlaceKind = PlaceKind.OTHER,
    ) = Candidate(name, detail, lat, lng, distanceM, SourceKind.SEARCH, category, kind)

    private val places = FakePlaces(
        PlacesRead.Places(
            listOf(
                SavedPlaceRow("home", 29.8, -95.5),
                SavedPlaceRow("the gym", 29.72, -95.42),
                SavedPlaceRow("work", 29.9, -95.6),
            ),
        ),
    )
    private val events = FakeEvents(EventsRead.Events(emptyList()))
    private val contacts = FakeContacts(ContactsRead.Contacts(emptyList()))
    private val search = FakeSearch()

    private fun resolver() = DestinationResolver(
        listOf(
            SavedPlaceSource(places),
            CalendarSource(events, nowMs = { noon }, zone = { ZoneId.of("UTC") }),
            ContactSource(contacts),
        ),
        search,
    )

    private fun resolve(text: String, c: LookupContext = ctx) = runBlocking { resolver().resolve(text, c) }

    // ------------------------------------------------------------------ order

    @Test fun aSavedPlaceWinsAndNothingElseIsAsked() {
        val r = resolve("take me home") as Resolution.Resolved
        assertEquals("home", r.destination.name)
        assertEquals(SourceKind.SAVED_PLACE, r.source)
        assertFalse(r.ambiguous)
        assertEquals(0, events.reads)
        assertTrue("search was never reached", search.queries.isEmpty())
        assertEquals(29.8, r.destination.latitude, 0.0)
    }

    @Test fun savedPlaceMatchesThroughArticlesAndFiller() {
        val r = resolve("navigate to the gym") as Resolution.Resolved
        assertEquals("the gym", r.destination.name)
        assertEquals(SourceKind.SAVED_PLACE, r.source)
    }

    @Test fun twoSavedPlacesThatMatchAreAmbiguousAndSearchIsNotConsulted() {
        places.read = PlacesRead.Places(
            listOf(SavedPlaceRow("gym north", 29.9, -95.4), SavedPlaceRow("gym south", 29.5, -95.4)),
        )
        search.reply = { SearchAnswer.Hits(listOf(hit("Some Gym"))) }
        val r = resolve("gym") as Resolution.Resolved
        assertTrue(r.ambiguous)
        assertEquals(2, r.candidates.size)
        assertEquals(SourceKind.SAVED_PLACE, r.source)
        assertTrue("an ambiguous saved place must not be skipped for a stranger's gym", search.queries.isEmpty())
        assertTrue("sorted nearest first", r.candidates[0].distanceM!! <= r.candidates[1].distanceM!!)
    }

    @Test fun nothingSavedFallsThroughToCalendarContactsThenSearch() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza"))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertEquals(SourceKind.SEARCH, r.source)
        assertEquals("the calendar was asked before search", 1, events.reads)
        assertEquals(fix, search.queries.single().near)
    }

    // ------------------------------------------------------------------ unreadable vs empty

    @Test fun anUnreadableSourceIsNotReportedAsEmptyEvenWhenSearchSucceeds() {
        places.read = PlacesRead.Unreadable("your saved places could not be read (disk)")
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza"))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertEquals(SourceKind.SEARCH, r.source)
        assertEquals(listOf("Couldn't read saved places: your saved places could not be read (disk)."), r.notes)
    }

    @Test fun aReadableEmptySourceLeavesNoNote() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza"))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertTrue(r.notes.isEmpty())
    }

    @Test fun notFoundNamesWhatWasEmptyAndWhatCouldNotBeRead() {
        places.read = PlacesRead.Unreadable("x")
        contacts.read = ContactsRead.Unreadable(ContactSource.NO_PERMISSION)
        search.reply = { SearchAnswer.NoMatch }
        val r = resolve("zzz qqq") as Resolution.NotFound
        assertTrue(r.message, r.message.startsWith("I couldn't find \"zzz qqq\"."))
        assertTrue(r.message.contains("Nothing matched in"))
        assertTrue(r.message.contains("Mapbox search"))
        assertTrue(r.message.contains("Couldn't read saved places"))
        val contactsNote = "Couldn't read contacts: contacts can't be read (contacts permission isn't granted)"
        assertTrue(r.message.contains(contactsNote))
        assertEquals(listOf(true, false, true, false), r.reports.map { it.unreadable })
    }

    @Test fun searchThatCouldNotBeReachedIsUnreadableNotNoMatch() {
        search.reply = { SearchAnswer.Failed("no connection") }
        val r = resolve("Joe's Pizza") as Resolution.NotFound
        assertTrue(r.message.contains("Couldn't read Mapbox search: search couldn't be reached: no connection."))
        val claimedEmpty = r.message.contains("Nothing matched in saved places, calendar, contacts, Mapbox")
        assertFalse("not claimed as empty", claimedEmpty)
    }

    @Test fun blankQueryIsNotFound() {
        assertTrue(resolve("   ") is Resolution.NotFound)
    }

    // ------------------------------------------------------------------ calendar

    private fun event(title: String, loc: String?, startMs: Long = noon + 3_600_000L) =
        CalendarEventRow(title, startMs, loc)

    @Test fun nextAppointmentWithALocationResolvesThroughSearchAtTripTime() {
        events.read = EventsRead.Events(listOf(event("Dentist", "Dr Lee, 12 Oak St, Houston")))
        search.reply = { SearchAnswer.Hits(listOf(hit("Dr Lee Dentistry", detail = "12 Oak St"))) }
        val r = resolve("my next appointment") as Resolution.Resolved
        assertEquals(SourceKind.CALENDAR, r.source)
        assertEquals("Dentist", r.destination.name)
        assertEquals("Dr Lee, 12 Oak St, Houston", search.queries.single().text)
        assertEquals(1, search.queries.single().limit)
        assertFalse(r.ambiguous)
    }

    @Test fun anEventWithNoLocationSaysSoAndNeverGuesses() {
        events.read = EventsRead.Events(listOf(event("Dentist", null)))
        search.reply = { SearchAnswer.Hits(listOf(hit("Wrong place"))) }
        val r = resolve("my next appointment") as Resolution.NotFound
        assertTrue(r.message, r.message.contains("\"Dentist\" is on your calendar but has no location"))
        assertTrue("never fell through to a search for it", search.queries.isEmpty())
    }

    @Test fun noEventsAndNoCalendarPermissionIsCannotSeeNotNone() {
        events.devicePermission = false
        val r = resolve("my next appointment") as Resolution.NotFound
        assertTrue(r.message.contains("calendar permission isn't granted"))
        assertTrue(r.reports.first { it.kind == SourceKind.CALENDAR }.unreadable)
    }

    @Test fun noEventsWithPermissionIsAnHonestNothing() {
        val r = resolve("my next appointment") as Resolution.NotFound
        assertFalse(r.reports.first { it.kind == SourceKind.CALENDAR }.unreadable)
        assertFalse(r.message.contains("permission"))
    }

    @Test fun anAddressDoesNotBlameTheCalendarPermission() {
        events.devicePermission = false
        search.reply = { SearchAnswer.Hits(listOf(hit("123 Main St"))) }
        val r = resolve("123 Main Street Houston") as Resolution.Resolved
        assertTrue("a street address is not a calendar question", r.notes.none { it.contains("calendar") })
    }

    @Test fun twoEventsWithDifferentLocationsAreAmbiguous() {
        events.read = EventsRead.Events(
            listOf(event("Dentist cleaning", "1 Oak St"), event("Dentist follow up", "9 Pine St", noon + 7_200_000L)),
        )
        search.reply = { q -> SearchAnswer.Hits(listOf(hit(q.text))) }
        val r = resolve("the dentist") as Resolution.Resolved
        assertTrue(r.ambiguous)
        assertEquals(listOf("Dentist cleaning", "Dentist follow up"), r.candidates.map { it.name })
    }

    @Test fun anEventAddressSearchThatFailsIsTerminalAndSaidInWords() {
        events.read = EventsRead.Events(listOf(event("Dentist", "12 Oak St")))
        search.reply = { SearchAnswer.Failed("no connection") }
        val r = resolve("my next appointment") as Resolution.NotFound
        assertTrue(r.message, r.message.contains("could not turn its address into a place"))
        assertEquals("not retried as a generic search of the phrase", 1, search.queries.size)
    }

    @Test fun anUnreadableCalendarFallsThroughAndIsNoted() {
        events.read = EventsRead.Unreadable("the calendar could not be read (db)")
        search.reply = { SearchAnswer.Hits(listOf(hit("Dentist Office"))) }
        val r = resolve("the dentist appointment") as Resolution.Resolved
        assertEquals(SourceKind.SEARCH, r.source)
        assertTrue(r.notes.single().contains("Couldn't read calendar"))
    }

    // ------------------------------------------------------------------ contacts

    @Test fun aContactAddressIsResolvedThroughSearch() {
        contacts.read = ContactsRead.Contacts(listOf(ContactAddress("Mia's mom", listOf("55 Elm St, Houston, TX"))))
        search.reply = { SearchAnswer.Hits(listOf(hit("55 Elm St"))) }
        val r = resolve("Mia's mom's house") as Resolution.Resolved
        assertEquals(SourceKind.CONTACTS, r.source)
        assertEquals("Mia's mom", r.destination.name)
        assertEquals("55 Elm St, Houston, TX", search.queries.single().text)
        assertTrue("both the whole phrase and the part before the possessive were tried", contacts.asked.size >= 2)
    }

    @Test fun aContactWithNoAddressSaysSo() {
        contacts.read = ContactsRead.Contacts(listOf(ContactAddress("Mia", emptyList())))
        val r = resolve("Mia") as Resolution.NotFound
        assertTrue(r.message, r.message.contains("Mia is in your contacts but has no address saved."))
        assertTrue(search.queries.isEmpty())
    }

    @Test fun aPartialContactMatchWithNoAddressDoesNotBlockASearch() {
        contacts.read = ContactsRead.Contacts(listOf(ContactAddress("Target Pharmacy", emptyList())))
        search.reply = { SearchAnswer.Hits(listOf(hit("Target"))) }
        val r = resolve("Target") as Resolution.Resolved
        assertEquals(SourceKind.SEARCH, r.source)
    }

    @Test fun contactsWithoutPermissionAreUnreadableAndNoted() {
        contacts.read = ContactsRead.Unreadable(ContactSource.NO_PERMISSION)
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza"))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertTrue(r.notes.single().contains("contacts permission isn't granted"))
    }

    // ------------------------------------------------------------------ search

    @Test fun oneClearSearchHitStartsWithoutAsking() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza"))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertFalse(r.ambiguous)
        assertTrue(r.sentence().startsWith("Going to Joe's Pizza"))
    }

    @Test fun severalDifferentHitsAreAmbiguousAndReadBackWithDistance() {
        search.reply = {
            SearchAnswer.Hits(
                listOf(hit("Pearl Cafe", lat = 29.7, lng = -95.4), hit("Pearl Diner", lat = 29.9, lng = -95.6)),
            )
        }
        val r = resolve("pearl") as Resolution.Resolved
        assertTrue(r.ambiguous)
        val s = r.sentence()
        assertTrue(s, s.startsWith("Several places match. Top pick: Pearl Cafe"))
        assertTrue(s.contains(" away)"))
        assertTrue(s.contains("Others: Pearl Diner"))
        assertTrue(s.endsWith("Is that the one?"))
    }

    @Test fun aChainWithManySameNameHitsMeansTheNearestOne() {
        search.reply = {
            SearchAnswer.Hits(listOf(hit("Starbucks"), hit("Starbucks", lat = 29.8), hit("Starbucks", lat = 29.9)))
        }
        val r = resolve("Starbucks") as Resolution.Resolved
        assertFalse(r.ambiguous)
    }

    @Test fun nearestXIsOneAnswerWithItsDistanceAndUsesTheCategory() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Shell"), hit("Chevron", lat = 29.75))) }
        val r = resolve("nearest gas station") as Resolution.Resolved
        assertFalse(r.ambiguous)
        val q = search.queries.single()
        assertEquals("gas_station", q.category)
        assertEquals("gas station", q.text)
        assertEquals(fix, q.near)
        assertNotNull(r.candidates.first().distanceM)
        assertTrue(r.sentence().contains("away"))
    }

    @Test fun nearestWithNoFixSaysSoInsteadOfGuessingAPlace() {
        val r = resolve("nearest gas station", LookupContext(null)) as Resolution.NotFound
        assertTrue(r.message.contains("I don't have your location yet"))
        assertTrue(search.queries.isEmpty())
    }

    @Test fun aViaSearchesAlongTheRouteAndTriesThePhraseAsACategory() {
        val route = listOf(GeoPoint(29.0, -95.0), GeoPoint(29.5, -95.5))
        search.reply = { SearchAnswer.Hits(listOf(hit("Whataburger"), hit("Chick-fil-A"))) }
        val r = resolve("fast food", LookupContext(fix, route)) as Resolution.Resolved
        assertFalse("an along-route ask is the closest, not a choice", r.ambiguous)
        val q = search.queries.single()
        assertEquals(route, q.alongRoute)
        assertEquals("fast_food", q.category)
    }

    @Test fun searchHitsGetADistanceFromTheLiveFixWhenTheSdkGaveNone() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Joe's Pizza", lat = 29.7, lng = -95.4, distanceM = null))) }
        val r = resolve("Joe's Pizza") as Resolution.Resolved
        assertEquals(0.0, r.candidates.single().distanceM!!, 1.0)
    }

    // ------------------------------------------------------------------ pure helpers

    @Test fun normalizeStripsFillerAndArticles() {
        assertEquals("gym", QueryText.normalize("Take me to the Gym!"))
        assertEquals("home", QueryText.normalize("navigate to my home"))
        assertEquals("joe's pizza", QueryText.normalize("Joe's Pizza"))
    }

    @Test fun searchPhraseParsesNearestAndCategories() {
        assertEquals(ParsedSearch("gas station", true, "gas_station"), SearchPhrase.parse("nearest gas station"))
        assertEquals(ParsedSearch("coffee", true, "coffee"), SearchPhrase.parse("closest coffee near me"))
        assertEquals(ParsedSearch("joe's pizza", false, null), SearchPhrase.parse("Joe's Pizza"))
    }

    @Test fun calendarPhrases() {
        assertEquals(CalendarPhrase.Next, CalendarPhrase.parse("my next appointment"))
        assertEquals(CalendarPhrase.Next, CalendarPhrase.parse("my appointment"))
        assertEquals(CalendarPhrase.Today, CalendarPhrase.parse("my appointment today"))
        assertEquals(CalendarPhrase.Title("dentist", false), CalendarPhrase.parse("the dentist"))
        assertEquals(CalendarPhrase.Title("dentist", true), CalendarPhrase.parse("my dentist appointment"))
        assertNull(CalendarPhrase.parse("go"))
    }

    @Test fun contactNameCandidates() {
        assertEquals(listOf("mia's mom's", "mia's mom", "mia"), ContactSource.candidateNames("Mia's mom's house"))
        assertEquals(listOf("mia"), ContactSource.candidateNames("take me to Mia"))
        assertTrue(ContactSource.candidateNames("x").isEmpty())
    }

    @Test fun routeSamplerKeepsTheEndpointsAndTheBudget() {
        val pts = List(1000) { GeoPoint(it.toDouble(), 0.0) }
        val s = RouteSampler.sample(pts, 100)
        assertEquals(100, s.size)
        assertEquals(pts.first(), s.first())
        assertEquals(pts.last(), s.last())
        assertEquals(pts.take(5), RouteSampler.sample(pts.take(5), 100))
    }

    @Test fun greatCircleDistanceIsSane() {
        val d = Geo.distanceM(GeoPoint(29.7604, -95.3698), GeoPoint(30.2672, -97.7431))
        assertEquals(235_000.0, d, 5_000.0)
        assertEquals(0.0, Geo.distanceM(fix, fix), 0.001)
    }

    // ---- device-run fixes (2026-10-03)

    @Test fun aFullAddressWhoseTopHitIsAnAddressIsNotAmbiguousBecauseOfBusinessesOnTheStreet() {
        // The run: "1000 N Navarro St, Victoria, TX" listed HOTWORX and WellMed beside the address itself.
        search.reply = {
            SearchAnswer.Hits(
                listOf(
                    hit("1000 N Navarro St", detail = "Victoria, TX", kind = PlaceKind.ADDRESS),
                    hit("HOTWORX", kind = PlaceKind.POI, category = "gym"),
                    hit("WellMed", kind = PlaceKind.POI, category = "clinic"),
                ),
            )
        }
        val r = resolve("1000 N Navarro St, Victoria, TX") as Resolution.Resolved
        assertFalse(r.ambiguous)
        assertEquals(listOf("1000 N Navarro St"), r.candidates.map { it.name })
    }

    @Test fun ambiguityAmongAddressesOfTheSameKindStillAsks() {
        search.reply = {
            SearchAnswer.Hits(
                listOf(
                    hit("1000 N Navarro St", detail = "Victoria, TX", kind = PlaceKind.ADDRESS),
                    hit("1000 N Navarro St", detail = "Victoria, BC", lat = 48.4, kind = PlaceKind.ADDRESS),
                    hit("HOTWORX", kind = PlaceKind.POI),
                ),
            )
        }
        val r = resolve("1000 N Navarro St") as Resolution.Resolved
        assertTrue(r.ambiguous)
        assertEquals(2, r.candidates.size)
    }

    @Test fun aBusinessNameIsStillAmbiguousAcrossPoiHits() {
        search.reply = {
            SearchAnswer.Hits(listOf(hit("Pearl Cafe", kind = PlaceKind.POI), hit("Pearl Diner", kind = PlaceKind.POI)))
        }
        assertTrue((resolve("pearl") as Resolution.Resolved).ambiguous)
    }

    @Test fun addressPhraseNeedsAHouseNumberThenAStreet() {
        assertTrue(AddressPhrase.looksLikeAddress("1000 N Navarro St, Victoria, TX"))
        assertTrue(AddressPhrase.looksLikeAddress("  12 Main Street"))
        assertFalse(AddressPhrase.looksLikeAddress("nearest gas station"))
        assertFalse(AddressPhrase.looksLikeAddress("1000"))
        assertFalse(AddressPhrase.looksLikeAddress("Navarro St"))
    }

    @Test fun categoryPhrasesGoThroughTheCategorySearchWithTheCanonicalId() {
        val cases = mapOf(
            "nearest gas station" to "gas_station",
            "closest coffee" to "coffee",
            "nearest pharmacy" to "pharmacy",
            "nearby grocery store" to "grocery",
            "nearest atm" to "atm",
            "closest hospital" to "hospital",
            "nearest ev charger" to "ev_charging_station",
        )
        cases.forEach { (phrase, id) ->
            search.queries.clear()
            search.reply = { SearchAnswer.Hits(listOf(hit("Somewhere"))) }
            resolve(phrase)
            assertEquals(phrase, id, search.queries.single().category)
        }
    }

    @Test fun aResultsCategoryAndAddressShowOnTheDestinationSoItCanBeToldApart() {
        search.reply = {
            val shell = hit("Shell", detail = "12 Main St", category = "gas station", kind = PlaceKind.POI)
            SearchAnswer.Hits(listOf(shell))
        }
        val r = resolve("nearest gas station") as Resolution.Resolved
        assertEquals("Gas station · 12 Main St", r.destination.detail)
        assertEquals("Gas station · 12 Main St", r.candidates.first().subtitle())
    }

    @Test fun aResultWithNoCategoryShowsJustItsAddress() {
        search.reply = { SearchAnswer.Hits(listOf(hit("Zain Corporation", detail = "9 Oak Ave"))) }
        val r = resolve("nearest gas station") as Resolution.Resolved
        assertEquals("9 Oak Ave", r.destination.detail)
    }
}
