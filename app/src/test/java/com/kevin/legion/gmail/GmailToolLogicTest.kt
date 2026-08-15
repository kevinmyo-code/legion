package com.kevin.legion.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure-logic coverage for [GmailToolLogic] - the query/cap/relative-date/failure-message rules
 * behind `search_mail`/`read_mail` (ticket 15). No Android, no GmailAuth, no GmailClient: this is
 * exactly the class ticket 15 named as the thing that must be a fast JVM test, not something only
 * exercised through a live Gmail call.
 */
class GmailToolLogicTest {

    // --- plan(): the briefing vs. search split, and the two hard caps ------------------

    @Test
    fun `blank query is the briefing - fixed query, cap 10, ignores limit entirely`() {
        val plan = GmailToolLogic.plan(query = null, limit = 100)
        assertEquals(GmailToolLogic.BRIEFING_QUERY, plan.query)
        assertTrue(plan.isBriefing)
        assertEquals(GmailToolLogic.BRIEFING_CAP, plan.cap)
    }

    @Test
    fun `empty-string query is also the briefing, not a search for the empty string`() {
        val plan = GmailToolLogic.plan(query = "   ", limit = null)
        assertEquals(GmailToolLogic.BRIEFING_QUERY, plan.query)
        assertTrue(plan.isBriefing)
    }

    @Test
    fun `a real query passes through to Gmail's q UNCHANGED - no app-side rewriting`() {
        val plan = GmailToolLogic.plan(query = "from:workshop subject:timing belt", limit = null)
        assertEquals("from:workshop subject:timing belt", plan.query)
        assertFalse(plan.isBriefing)
    }

    @Test
    fun `search defaults to cap 5 with no limit supplied`() {
        val plan = GmailToolLogic.plan(query = "invoice", limit = null)
        assertEquals(GmailToolLogic.SEARCH_CAP, plan.cap)
    }

    @Test
    fun `search never exceeds cap 5 even if the model asks for more`() {
        val plan = GmailToolLogic.plan(query = "invoice", limit = 500)
        assertEquals(GmailToolLogic.SEARCH_CAP, plan.cap)
    }

    @Test
    fun `search may ask for fewer than the cap`() {
        val plan = GmailToolLogic.plan(query = "invoice", limit = 2)
        assertEquals(2, plan.cap)
    }

    @Test
    fun `a zero or negative limit falls back to the default cap, not zero results`() {
        assertEquals(GmailToolLogic.SEARCH_CAP, GmailToolLogic.plan(query = "invoice", limit = 0).cap)
        assertEquals(GmailToolLogic.SEARCH_CAP, GmailToolLogic.plan(query = "invoice", limit = -3).cap)
    }

    @Test
    fun `briefing query is exactly ticket 05's fixed string`() {
        assertEquals("is:unread in:inbox category:primary newer_than:2d", GmailToolLogic.BRIEFING_QUERY)
    }

    // --- relativeMailDate(): day-granularity label, pure function of two millis --------

    private fun millisAt(zonedDate: ZonedDateTime): Long = zonedDate.toInstant().toEpochMilli()

    @Test
    fun `same calendar day reads as today`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 13, 18, 0, 0, 0, zone)
        val earlierToday = ZonedDateTime.of(2026, 8, 13, 7, 30, 0, 0, zone)
        assertEquals("today", GmailToolLogic.relativeMailDate(millisAt(earlierToday), millisAt(now)))
    }

    @Test
    fun `the previous calendar day reads as yesterday`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 13, 9, 0, 0, 0, zone)
        val yesterday = ZonedDateTime.of(2026, 8, 12, 22, 0, 0, 0, zone)
        assertEquals("yesterday", GmailToolLogic.relativeMailDate(millisAt(yesterday), millisAt(now)))
    }

    @Test
    fun `three days back reads as N days ago`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 13, 9, 0, 0, 0, zone)
        val threeDaysAgo = ZonedDateTime.of(2026, 8, 10, 9, 0, 0, 0, zone)
        assertEquals("3 days ago", GmailToolLogic.relativeMailDate(millisAt(threeDaysAgo), millisAt(now)))
    }

    @Test
    fun `a week or more back falls back to a short date, not an ever-growing day count`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 13, 9, 0, 0, 0, zone)
        val overAWeekAgo = ZonedDateTime.of(2026, 8, 1, 9, 0, 0, 0, zone)
        val label = GmailToolLogic.relativeMailDate(millisAt(overAWeekAgo), millisAt(now))
        assertFalse(label.endsWith("days ago"))
        assertTrue(label.contains("Aug"))
    }

    @Test
    fun `an unset zero timestamp never claims a real date`() {
        assertEquals("unknown date", GmailToolLogic.relativeMailDate(0L, Instant.now().toEpochMilli()))
    }

    // --- causeForNeedsConsent() / causeForFailure(): the four ticket-10 failure causes -

    @Test
    fun `never granted before wins never-granted, not needs-reauthorising`() {
        assertEquals(GmailToolLogic.Cause.NEVER_GRANTED, GmailToolLogic.causeForNeedsConsent(everGranted = false))
    }

    @Test
    fun `granted before then lapsed or revoked reads as needs-reauthorising`() {
        assertEquals(GmailToolLogic.Cause.NEEDS_CONSENT, GmailToolLogic.causeForNeedsConsent(everGranted = true))
    }

    @Test
    fun `a network exception reads as no-network`() {
        assertEquals(GmailToolLogic.Cause.NO_NETWORK, GmailToolLogic.causeForFailure(isNetworkException = true))
    }

    @Test
    fun `a non-network failure reads as a genuine api error`() {
        assertEquals(GmailToolLogic.Cause.API_ERROR, GmailToolLogic.causeForFailure(isNetworkException = false))
    }

    // --- message(): the four distinct lines, verbatim from ticket 10's Answer table ----

    @Test
    fun `the four messages are all distinct - never a collapsed generic one`() {
        val messages = GmailToolLogic.Cause.entries.map { GmailToolLogic.message(it) }
        assertEquals(4, messages.toSet().size)
    }

    @Test
    fun `no-network message is exact`() {
        assertEquals("I can't reach Gmail - no connection.", GmailToolLogic.message(GmailToolLogic.Cause.NO_NETWORK))
    }

    @Test
    fun `never-granted message is exact`() {
        assertEquals(
            "You haven't given me access to Gmail yet.",
            GmailToolLogic.message(GmailToolLogic.Cause.NEVER_GRANTED),
        )
    }

    @Test
    fun `needs-consent message routes through GoogleGrantResolver, naming Gmail`() {
        assertEquals(
            "Gmail needs re-authorising. It's in Setup, under Google.",
            GmailToolLogic.message(GmailToolLogic.Cause.NEEDS_CONSENT),
        )
    }

    @Test
    fun `api-error message is exact`() {
        assertEquals(
            "Gmail returned an error - I'll not guess at what's in there.",
            GmailToolLogic.message(GmailToolLogic.Cause.API_ERROR),
        )
    }
}
