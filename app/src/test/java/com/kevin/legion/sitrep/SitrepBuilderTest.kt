package com.kevin.legion.sitrep

import com.kevin.legion.calendar.OpenerCalendarBriefing
import com.kevin.legion.news.FeedFetchResult
import com.kevin.legion.news.FeedHeadline
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
            OpenerCalendarBriefing.BriefingEvent(
                title = "Dentist", startMs = now + 3_600_000, endMs = now + 5_400_000, allDay = false,
            ),
            OpenerCalendarBriefing.BriefingEvent(
                title = "Kevin's birthday", startMs = now, endMs = now + 86_400_000, allDay = true,
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
        val ended = OpenerCalendarBriefing.BriefingEvent(
            title = "Yesterday's standup", startMs = now - 7_200_000, endMs = now - 3_600_000, allDay = false,
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

    // --------------------------------------------------------------------------- feeds (ticket 10)

    @Test
    fun `feedResultSentence names the feed and reports headlines verbatim`() {
        val sentence = SitrepBuilder.feedResultSentence(
            "HN frontpage",
            FeedFetchResult.Success(listOf(FeedHeadline("Rust 2.0 released", null), FeedHeadline("A new database", null))),
        )
        assertTrue(sentence.startsWith("HN frontpage:"))
        assertTrue(sentence.contains("Rust 2.0 released"))
        assertTrue(sentence.contains("A new database"))
    }

    @Test
    fun `feedResultSentence caps headlines per feed`() {
        val items = (1..10).map { FeedHeadline("Headline $it", null) }
        val sentence = SitrepBuilder.feedResultSentence("Big feed", FeedFetchResult.Success(items))
        assertTrue(sentence.contains("Headline 5"))
        assertTrue("a feed's own headline cap must not let a busy feed dump every item", !sentence.contains("Headline 6"))
    }

    // Ticket 07's four RSS sentences must stay four after composition, not flatten into three -
    // the ticket's own explicit ask.
    @Test
    fun `the four RSS outcome sentences stay four distinct sentences after feedResultSentence`() {
        val sentences = setOf(
            SitrepBuilder.feedResultSentence("f", FeedFetchResult.Success(listOf(FeedHeadline("h", null)))),
            SitrepBuilder.feedResultSentence("f", FeedFetchResult.Empty),
            SitrepBuilder.feedResultSentence("f", FeedFetchResult.Unreachable("HTTP 404")),
            SitrepBuilder.feedResultSentence("f", FeedFetchResult.Unparseable("bad xml")),
        )
        assertEquals(4, sentences.size)
    }

    @Test
    fun `an unreachable feed and a quiet feed read as different sentences, never the same one`() {
        val unreachable = SitrepBuilder.feedResultSentence("f", FeedFetchResult.Unreachable("HTTP 404"))
        val empty = SitrepBuilder.feedResultSentence("f", FeedFetchResult.Empty)
        assertTrue(!unreachable.contains("nothing new"))
        assertTrue(!empty.contains("unreachable"))
    }

    @Test
    fun `feedsSection is null with no subscriptions, not an empty-looking sentence`() {
        assertEquals(null, SitrepBuilder.feedsSection(emptyList()))
    }

    @Test
    fun `feedsSection carries its own NEWS FEEDS label, distinct from the mail NEWS label`() {
        val line = SitrepBuilder.feedsSection(listOf("f" to FeedFetchResult.Empty))
        assertTrue(line != null && line.startsWith("NEWS FEEDS "))
    }

    // ------------------------------------------------------------------ newsBlock composition

    @Test
    fun `mail summarized and feeds fine renders both halves under their own labels`() {
        val block = SitrepBuilder.newsBlock(
            SitrepBuilder.NewsOutcome.Summarized("Two stories on AI chips."),
            listOf("HN frontpage" to FeedFetchResult.Success(listOf(FeedHeadline("Rust 2.0 released", null)))),
        )
        assertTrue(block.contains("Two stories on AI chips."))
        assertTrue(block.contains("HN frontpage"))
        assertTrue(block.contains("Rust 2.0 released"))
        assertTrue(block.startsWith("NEWS "))
        assertTrue("both halves must be present as two distinguishable labelled lines", block.contains("NEWS FEEDS"))
    }

    // The ticket's own reason for existing: a lapsed Gmail grant must never blank the feed half.
    @Test
    fun `mail could not check but feeds fine - the feed half still renders`() {
        val block = SitrepBuilder.newsBlock(
            SitrepBuilder.NewsOutcome.CouldNotCheck("no Gmail grant"),
            listOf("HN frontpage" to FeedFetchResult.Success(listOf(FeedHeadline("Rust 2.0 released", null)))),
        )
        assertTrue(block.contains("could not check - no Gmail grant"))
        assertTrue("the feed half must still report even though mail failed", block.contains("Rust 2.0 released"))
    }

    // The ticket's other named case: a dead feed must never blank a working newsletter summary.
    @Test
    fun `mail fine but one feed is unreachable - the summary still renders and the dead feed is named`() {
        val block = SitrepBuilder.newsBlock(
            SitrepBuilder.NewsOutcome.Summarized("Two stories on AI chips."),
            listOf("HN frontpage" to FeedFetchResult.Unreachable("HTTP 404")),
        )
        assertTrue("the newsletter summary must still arrive despite a dead feed", block.contains("Two stories on AI chips."))
        assertTrue("the dead feed must be named, not silently dropped", block.contains("HN frontpage"))
        assertTrue(block.contains("unreachable - HTTP 404"))
    }

    @Test
    fun `both halves empty renders two distinct empty sentences, not one collapsed line`() {
        val block = SitrepBuilder.newsBlock(
            SitrepBuilder.NewsOutcome.Empty,
            listOf("HN frontpage" to FeedFetchResult.Empty),
        )
        assertTrue(block.contains("no newsletters in the last day"))
        assertTrue(block.contains("HN frontpage: nothing new"))
    }

    @Test
    fun `both halves failed renders two distinct failure sentences, neither swallows the other`() {
        val block = SitrepBuilder.newsBlock(
            SitrepBuilder.NewsOutcome.SummaryFailed(3),
            listOf("HN frontpage" to FeedFetchResult.Unparseable("bad xml")),
        )
        assertTrue(block.contains("3 newsletter(s)") && block.contains("summary failed"))
        assertTrue(block.contains("HN frontpage: could not be read - bad xml"))
    }

    @Test
    fun `newsBlock omits the feeds half entirely when there are no subscriptions`() {
        val block = SitrepBuilder.newsBlock(SitrepBuilder.NewsOutcome.Summarized("Two stories."), emptyList())
        assertEquals(SitrepBuilder.newsSection(SitrepBuilder.NewsOutcome.Summarized("Two stories.")), block)
        assertTrue(!block.contains("NEWS FEEDS"))
    }
}
