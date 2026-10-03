package com.kevin.legion.navigation.resolve

import com.kevin.legion.navigation.GeoPoint
import java.time.Instant
import java.time.ZoneId

// ---------------------------------------------------------------------------------------------
// Saved places (ticket 03, source 1)
// ---------------------------------------------------------------------------------------------

/** What a read of the saved places said. A thrown or refused read is [Unreadable], never an empty list. */
sealed interface PlacesRead {
    data class Places(val places: List<SavedPlaceRow>) : PlacesRead

    data class Unreadable(val why: String) : PlacesRead
}

data class SavedPlaceRow(val label: String, val latitude: Double, val longitude: Double)

fun interface SavedPlacesReader {
    suspend fun read(): PlacesRead
}

/**
 * `tag_place` labels: "home", "work", "the gym". Coordinates come straight from the place, no
 * search. An exact label wins outright; a label that merely contains (or is contained in) the
 * phrase is a match, and several of those are ambiguous.
 */
class SavedPlaceSource(private val reader: SavedPlacesReader) : DestinationSource {
    override val kind = SourceKind.SAVED_PLACE

    @Suppress("ReturnCount") // guard clauses: unreadable, empty phrase, no match, then the answer
    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val places = when (val r = reader.read()) {
            is PlacesRead.Unreadable -> return SourceAnswer.Unreadable(r.why)
            is PlacesRead.Places -> r.places
        }
        val q = QueryText.normalize(query)
        if (q.isEmpty()) return SourceAnswer.NoMatch
        val named = places.map { it to QueryText.normalize(it.label) }
        val exact = named.filter { (_, n) -> n == q }.map { it.first }
        val matched = exact.ifEmpty {
            named.filter { (_, n) -> n.length >= MIN_PARTIAL && (n.contains(q) || q.contains(n)) }.map { it.first }
        }
        if (matched.isEmpty()) return SourceAnswer.NoMatch
        val candidates = matched.map { p ->
            Candidate(
                name = p.label,
                detail = "saved place",
                latitude = p.latitude,
                longitude = p.longitude,
                distanceM = ctx.fix?.let { Geo.distanceM(it, GeoPoint(p.latitude, p.longitude)) },
                source = kind,
            )
        }.sortedBy { it.distanceM ?: Double.MAX_VALUE }
        return SourceAnswer.Hits(candidates, ambiguous = candidates.size > 1)
    }

    private companion object {
        const val MIN_PARTIAL = 3
    }
}

// ---------------------------------------------------------------------------------------------
// Calendar (ticket 03, source 2)
// ---------------------------------------------------------------------------------------------

data class CalendarEventRow(val title: String, val startMs: Long, val location: String?)

sealed interface EventsRead {
    data class Events(val events: List<CalendarEventRow>) : EventsRead

    data class Unreadable(val why: String) : EventsRead
}

interface UpcomingEvents {
    /** Upcoming events from [nowMs] on, soonest first. */
    suspend fun upcoming(nowMs: Long): EventsRead

    /**
     * Whether the phone's calendar permission is granted. LEGION's agenda is its own Room store, but
     * the Google import into it needs that permission, so without it the store can be missing
     * appointments this phone cannot see: "no such event" is then not something we can say
     * (the same reading `OpenerCalendarBriefing` documents).
     */
    fun canReadDeviceCalendar(): Boolean
}

/**
 * "my next appointment", "today's meeting", "the dentist" taken apart. [Title.explicit] is true when
 * the phrase said an event word ("my dentist appointment"): only then does "I could not read the
 * calendar" belong in the answer, because a bare "the dentist" or a street address is not clearly
 * about the calendar at all.
 */
sealed interface CalendarPhrase {
    data object Next : CalendarPhrase

    data object Today : CalendarPhrase

    data class Title(val term: String, val explicit: Boolean) : CalendarPhrase

    companion object {
        private val EVENT_WORDS = Regex("\\b(appointment|appointments|meeting|meetings|event|events)\\b")
        private val FILLER = setOf(
            "the", "my", "a", "an", "to", "take", "me", "go", "navigate", "appointment", "appointments",
            "meeting", "meetings", "event", "events", "for", "at", "next", "upcoming", "today", "todays", "today's",
        )

        fun parse(text: String): CalendarPhrase? {
            val t = QueryText.normalize(text)
            val hasEventWord = EVENT_WORDS.containsMatchIn(t)
            val words = t.split(' ').filter { it.isNotEmpty() }
            val term = words.filter { it !in FILLER }.joinToString(" ")
            return when {
                term.isEmpty() && hasEventWord -> if ("today" in words || "today's" in words) Today else Next
                term.length >= MIN_TERM -> Title(term, hasEventWord)
                else -> null
            }
        }

        private const val MIN_TERM = 3
    }
}

/**
 * Event locations. The matched event's location TEXT is then resolved through search at trip time
 * (the resolver does that); the text is used and dropped, never written anywhere new. **An event
 * with no location says so and the resolver stops** (never a guessed place).
 */
class CalendarSource(
    private val events: UpcomingEvents,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DestinationSource {
    override val kind = SourceKind.CALENDAR

    @Suppress("ReturnCount") // guard clauses: no phrase, unreadable, nothing found, no location, then the answer
    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val phrase = CalendarPhrase.parse(query) ?: return SourceAnswer.NoMatch
        val now = nowMs()
        val upcoming = when (val r = events.upcoming(now)) {
            is EventsRead.Unreadable -> return SourceAnswer.Unreadable(r.why)
            is EventsRead.Events -> r.events.sortedBy { it.startMs }
        }
        val matches = when (phrase) {
            CalendarPhrase.Next -> upcoming.take(1)
            CalendarPhrase.Today -> {
                val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
                upcoming.filter { Instant.ofEpochMilli(it.startMs).atZone(zone()).toLocalDate() == today }.take(1)
            }
            is CalendarPhrase.Title -> {
                val tokens = phrase.term.split(' ')
                upcoming.filter { e -> tokens.all { it in e.title.lowercase() } }
            }
        }
        if (matches.isEmpty()) {
            // Nothing found. With no calendar permission that is "can't see", not "none" - but only
            // said when the phrase was clearly about the calendar.
            val aboutCalendar = phrase !is CalendarPhrase.Title || phrase.explicit
            return if (events.canReadDeviceCalendar() || !aboutCalendar) {
                SourceAnswer.NoMatch
            } else {
                SourceAnswer.Unreadable(NO_PERMISSION)
            }
        }
        val top = matches.first()
        val withLocation = matches.filter { !it.location.isNullOrBlank() }
        if (withLocation.isEmpty()) {
            return SourceAnswer.NoAddress(
                "\"${top.title}\" is on your calendar but has no location, so I have nowhere to take you.",
            )
        }
        val items = withLocation.distinctBy { it.location!!.trim().lowercase() }
            .map { AddressToSearch(label = it.title, text = it.location!!.trim()) }
        return SourceAnswer.NeedsSearch(items)
    }

    companion object {
        const val NO_PERMISSION =
            "the calendar can't be fully read (calendar permission isn't granted), so I can't rule an event out"
    }
}

// ---------------------------------------------------------------------------------------------
// Contacts (ticket 03, source 3)
// ---------------------------------------------------------------------------------------------

data class ContactAddress(val name: String, val addresses: List<String>)

sealed interface ContactsRead {
    data class Contacts(val contacts: List<ContactAddress>) : ContactsRead

    data class Unreadable(val why: String) : ContactsRead
}

fun interface ContactsReader {
    /** Contacts whose display name contains any of [names]. A refused permission is Unreadable, never an empty list. */
    suspend fun read(names: List<String>): ContactsRead
}

/** Address text on a contact ("Mia's mom"). No address on the contact is said in words. */
class ContactSource(private val reader: ContactsReader) : DestinationSource {
    override val kind = SourceKind.CONTACTS

    @Suppress("ReturnCount") // guard clauses: no name, unreadable, no contact, no address, then the answer
    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val names = candidateNames(query)
        if (names.isEmpty()) return SourceAnswer.NoMatch
        val contacts = when (val r = reader.read(names)) {
            is ContactsRead.Unreadable -> return SourceAnswer.Unreadable(r.why)
            is ContactsRead.Contacts -> r.contacts
        }
        if (contacts.isEmpty()) return SourceAnswer.NoMatch
        val withAddress = contacts.filter { it.addresses.isNotEmpty() }
        if (withAddress.isEmpty()) {
            // Said in words only for a contact whose whole name the phrase IS. A partial LIKE hit with
            // no address ("Target Pharmacy" for "Target": a business saved with only a phone number)
            // must not stop a search for the business.
            val named = contacts.firstOrNull { c -> QueryText.normalize(c.name) in names }
                ?: return SourceAnswer.NoMatch
            return SourceAnswer.NoAddress("${named.name} is in your contacts but has no address saved.")
        }
        val items = withAddress.flatMap { c -> c.addresses.map { AddressToSearch(label = c.name, text = it) } }
            .distinctBy { it.text.trim().lowercase() }
        return SourceAnswer.NeedsSearch(items)
    }

    companion object {
        const val NO_PERMISSION = "contacts can't be read (contacts permission isn't granted)"

        private val PLACE_TAIL = Regex("\\s+(house|place|home|address|apartment|office)$")

        /**
         * The names to look for: the phrase without a trailing "house", that without a trailing
         * possessive ("Mia's mom's" names the contact "Mia's mom"), and the part before the first one.
         */
        fun candidateNames(query: String): List<String> {
            val q = QueryText.normalize(query).replace(PLACE_TAIL, "").trim()
            if (q.length < MIN_NAME) return emptyList()
            val head = q.substringBefore("'s").trim()
            return listOf(q, q.removeSuffix("'s").trim(), head).filter { it.length >= MIN_NAME }.distinct()
        }

        private const val MIN_NAME = 2
    }
}
