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

    @Test
    fun `newsSection renders every outcome distinctly`() {
        val notConfigured = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.NotConfigured)
        assertTrue(notConfigured.contains("no newsletter senders configured"))

        val couldNotCheck = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.CouldNotCheck("no Gmail grant"))
        assertTrue(couldNotCheck.contains("could not check"))
        assertTrue(couldNotCheck.contains("no Gmail grant"))

        val empty = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Empty)
        assertTrue(empty.contains("nothing from your newsletters"))

        val summaryFailed = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.SummaryFailed(3))
        assertTrue(summaryFailed.contains("3 newsletter(s)"))
        assertTrue(summaryFailed.contains("not logged"))

        val summarized = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Summarized("Two stories on AI chips."))
        assertTrue(summarized.contains("Two stories on AI chips."))
    }

    @Test
    fun `every section carries the NEWS label so a listener can tell which module spoke`() {
        val line = SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Empty)
        assertTrue(line.startsWith("NEWS "))
    }
}
