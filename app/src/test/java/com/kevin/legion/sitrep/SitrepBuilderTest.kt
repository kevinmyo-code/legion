package com.kevin.legion.sitrep

import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.weather.WeatherController
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [SitrepBuilder] - ticket 22's own verification requirement ("`get_sitrep`
 * covered where it is pure: section formatting, module filtering"). No Room, no `Context`, no
 * network - the same "plain JVM unit test target" split [HomeDigestBuilderTest] already uses for
 * the advisor digest builders this file's sections deliberately match the vocabulary of.
 */
class SitrepBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // ------------------------------------------------------------------------ module filtering

    @Test
    fun `no filter passes every enabled module through unchanged`() {
        val enabled = setOf(SitrepModule.CALENDAR, SitrepModule.FLEET)
        assertEquals(enabled, SitrepBuilder.resolveRequestedModules(null, enabled))
    }

    @Test
    fun `a filter narrows enabled modules, it never widens them`() {
        val enabled = setOf(SitrepModule.CALENDAR, SitrepModule.WEATHER)
        // NEWS is requested but not enabled - it must not appear in the result even though it was
        // explicitly asked for by name.
        val requested = setOf(SitrepModule.CALENDAR, SitrepModule.NEWS)
        assertEquals(setOf(SitrepModule.CALENDAR), SitrepBuilder.resolveRequestedModules(requested, enabled))
    }

    @Test
    fun `a filter naming only disabled modules resolves to empty, not to everything`() {
        val enabled = setOf(SitrepModule.CALENDAR)
        val requested = setOf(SitrepModule.NEWS)
        assertEquals(emptySet<SitrepModule>(), SitrepBuilder.resolveRequestedModules(requested, enabled))
    }

    // -------------------------------------------------------------------------------- compose

    @Test
    fun `compose joins sections in SitrepModule declaration order, not map iteration order`() {
        // Deliberately inserted out of order, so a naive `sections.values.joinToString` would fail
        // this test while the real ordering-by-[order] implementation passes it.
        val sections = mapOf(
            SitrepModule.NEWS to "NEWS x",
            SitrepModule.CALENDAR to "CALENDAR x",
            SitrepModule.WEATHER to "WEATHER x",
        )
        val text = SitrepBuilder.compose(SitrepModule.entries, sections)
        val calendarIdx = text.indexOf("CALENDAR x")
        val weatherIdx = text.indexOf("WEATHER x")
        val newsIdx = text.indexOf("NEWS x")
        assertTrue(calendarIdx in 0 until weatherIdx)
        assertTrue(weatherIdx in 0 until newsIdx)
    }

    @Test
    fun `compose skips a module with no section, never a blank line for it`() {
        val text = SitrepBuilder.compose(SitrepModule.entries, mapOf(SitrepModule.WEATHER to "WEATHER only"))
        assertEquals("WEATHER only", text)
    }

    // ----------------------------------------------------------------------------- calendar

    @Test
    fun `calendarSection with no permission never claims a clear day`() {
        val line = SitrepBuilder.calendarSection(hasPermission = false, events = emptyList(), nowMs = 0L, zone = zone)
        assertTrue(line.contains("CALENDAR"))
        assertTrue(line.contains("no permission"))
        assertTrue("a refused permission must never read as an empty, clear calendar", !line.contains("clear"))
    }

    @Test
    fun `calendarSection with permission and no events reads clear, a real computed state`() {
        val line = SitrepBuilder.calendarSection(hasPermission = true, events = emptyList(), nowMs = 0L, zone = zone)
        assertTrue(line.contains("CALENDAR"))
        assertTrue(line.contains("clear"))
    }

    @Test
    fun `calendarSection names a timed event and an all-day event distinctly`() {
        val now = 1_700_000_000_000L
        val events = listOf(
            CalendarProvider.GoogleCalendarEvent(
                eventId = 1, calendarId = 1, title = "Dentist", startMs = now + 3_600_000, endMs = now + 5_400_000, allDay = false,
            ),
            CalendarProvider.GoogleCalendarEvent(
                eventId = 2, calendarId = 1, title = "Kevin's birthday", startMs = now, endMs = now + 86_400_000, allDay = true,
            ),
        )
        val line = SitrepBuilder.calendarSection(hasPermission = true, events = events, nowMs = now, zone = zone)
        assertTrue(line.contains("Dentist"))
        assertTrue(line.contains("Kevin's birthday"))
        assertTrue(line.contains("all day"))
    }

    @Test
    fun `calendarSection drops an event that already ended`() {
        val now = 1_700_000_000_000L
        val ended = CalendarProvider.GoogleCalendarEvent(
            eventId = 1, calendarId = 1, title = "Yesterday's standup", startMs = now - 7_200_000, endMs = now - 3_600_000, allDay = false,
        )
        val line = SitrepBuilder.calendarSection(hasPermission = true, events = listOf(ended), nowMs = now, zone = zone)
        assertTrue(line.contains("clear"))
        assertTrue(!line.contains("standup"))
    }

    // ------------------------------------------------------------------------------ weather

    @Test
    fun `weatherSection with no fix reads not logged, never a fabricated reading`() {
        val line = SitrepBuilder.weatherSection(null)
        assertTrue(line.contains("WEATHER"))
        assertTrue(line.contains("not logged"))
    }

    @Test
    fun `weatherSection with caution conditions says so`() {
        val info = WeatherController.WeatherInfo(tempF = 55, description = "rainy", caution = true)
        val line = SitrepBuilder.weatherSection(info)
        assertTrue(line.contains("55"))
        assertTrue(line.contains("rainy"))
        assertTrue(line.contains("drive safe"))
    }

    @Test
    fun `weatherSection with ordinary conditions carries no caution suffix`() {
        val info = WeatherController.WeatherInfo(tempF = 72, description = "bright and clear", caution = false)
        val line = SitrepBuilder.weatherSection(info)
        assertTrue(!line.contains("drive safe"))
    }

    // ------------------------------------------------------------------------------- news

    @Test
    fun `buildNewsletterQuery is null when no senders are configured`() {
        assertEquals(null, SitrepBuilder.buildNewsletterQuery(emptyList()))
        assertEquals(null, SitrepBuilder.buildNewsletterQuery(listOf("  ", "")))
    }

    @Test
    fun `buildNewsletterQuery joins senders with OR and drops blanks`() {
        val query = SitrepBuilder.buildNewsletterQuery(listOf("a@x.com", " ", "b@y.com"))
        assertEquals("from:(a@x.com OR b@y.com) newer_than:1d", query)
    }

    // command-center ticket 12: no-config default query, pinned exactly - a stranger's Gmail
    // account with zero curated senders must still find newsletter-shaped mail, and this exact
    // string is the deterministic, testable line between "newsletter" and "personal email".
    @Test
    fun `NO_CONFIG_NEWSLETTER_QUERY text is pinned exactly`() {
        assertEquals(
            "(category:updates OR category:promotions) unsubscribe newer_than:1d",
            SitrepBuilder.NO_CONFIG_NEWSLETTER_QUERY,
        )
    }

    @Test
    fun `resolveNewsletterQuery falls back to the no-config default when nothing is curated`() {
        assertEquals(SitrepBuilder.NO_CONFIG_NEWSLETTER_QUERY, SitrepBuilder.resolveNewsletterQuery(emptyList()))
        assertEquals(SitrepBuilder.NO_CONFIG_NEWSLETTER_QUERY, SitrepBuilder.resolveNewsletterQuery(listOf("  ", "")))
    }

    @Test
    fun `resolveNewsletterQuery prefers a curated sender list over the default - override, not a casualty`() {
        val query = SitrepBuilder.resolveNewsletterQuery(listOf("a@x.com", "b@y.com"))
        assertEquals("from:(a@x.com OR b@y.com) newer_than:1d", query)
        assertTrue("a curated list must win outright, never merge with the default query", !query.contains("category:"))
    }

    @Test
    fun `newsSection renders every outcome distinctly`() {
        val couldNotCheck = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.CouldNotCheck("no Gmail grant"))
        assertTrue(couldNotCheck.contains("could not check"))
        assertTrue(couldNotCheck.contains("no Gmail grant"))

        val empty = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Empty)
        assertTrue(empty.contains("no newsletters in the last day"))

        val summaryFailed = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.SummaryFailed(3))
        assertTrue(summaryFailed.contains("3 newsletter(s)"))
        assertTrue(summaryFailed.contains("not logged"))

        val summarized = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Summarized("Two stories on AI chips."))
        assertTrue(summarized.contains("Two stories on AI chips."))
    }

    // Ticket 12's "three distinct answers": empty, unreachable, and summary-failed must never
    // collapse into the same wording, and none of them may borrow another's phrase.
    @Test
    fun `the three failure-shaped NEWS sentences are textually distinct from each other`() {
        val empty = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Empty)
        val couldNotCheck = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.CouldNotCheck("no connection"))
        val summaryFailed = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.SummaryFailed(2))

        assertTrue(empty.contains("no newsletters in the last day"))
        assertTrue(couldNotCheck.contains("could not check"))
        assertTrue(summaryFailed.contains("2 newsletter(s)") && summaryFailed.contains("summary failed"))

        val sentences = setOf(empty, couldNotCheck, summaryFailed)
        assertEquals("all three must be distinct strings, not just distinct types", 3, sentences.size)
        assertTrue("empty must never claim a failure", !empty.contains("could not") && !empty.contains("failed"))
        assertTrue("an unreachable mailbox must never claim a computed zero", !couldNotCheck.contains("no newsletters"))
        assertTrue("a summary failure must never read as a clean empty result", !summaryFailed.contains("no newsletters in the last day"))
    }

    @Test
    fun `every section carries the NEWS label so a listener can tell which module spoke`() {
        val line = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Empty)
        assertTrue(line.startsWith("NEWS "))
    }
}
