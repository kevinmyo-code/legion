package com.kevin.legion.watches

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-evaluated `mail_reply` watch (ADR 0083). Matching is code over headers; the line says
 * only a company name from HIS list and how long ago; nothing from mail is stored. Made-up
 * companies, addresses and subjects only.
 */
class MailReplyCheckTest {

    private class MapCursor : MailReplyCursor {
        var since: Long? = null
        var blind: String? = null
        override fun get() = since
        override fun set(ms: Long?) { since = ms }
        override fun cannotSee(sentence: String?) { blind = sentence }
        override fun cannotSeeSentence() = blind
    }

    private val minute = 60_000L
    private var now = 10_000L * minute
    private val cursor = MapCursor()
    private var companies: List<List<String>>? = listOf(listOf("Acme", "Example Corp", "initech.example"))
    private var mail: MailCheck = MailCheck.Found(emptyList())
    private val queries = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private var delivery = WatchDelivery.SPOKEN

    private val check = MailReplyCheck(
        companyLists = { companies },
        search = { q -> queries += q; mail },
        cursor = cursor,
        announce = { facts -> said += facts; delivery },
        clockMs = { now },
    )

    @Test
    fun `a company on his list matches by domain, display name or subject, and nothing else`() {
        val list = listOf("Acme", "Example Corp", "initech.example")
        assertEquals("Acme", MailReplyMatch.company(list, "Acme Recruiting <jobs@ats.example>", "Your application"))
        assertEquals(
            "Example Corp",
            MailReplyMatch.company(list, "Careers <no-reply@ats.example>", "Example Corp: next steps"),
        )
        assertEquals("initech.example", MailReplyMatch.company(list, "Pat <pat@mail.initech.example>", "Hello"))
        assertEquals("Example Corp", MailReplyMatch.company(list, "HR <hr@examplecorp.example>", "Hi"))
        assertNull(MailReplyMatch.company(list, "Acmeville Times <news@acmeville.example>", "Weekly news"))
        assertNull(MailReplyMatch.company(list, "A friend <friend@mail.example>", "Lunch?"))
    }

    @Test
    fun `the first check only starts the clock, so old mail is never news`() = runBlocking {
        mail = MailCheck.Found(listOf(MailHeaders("Acme <a@acme.example>", "Hi", now - minute)))
        assertTrue(check.run(now).isEmpty())
        assertEquals(now, cursor.since)
        assertTrue(queries.isEmpty() && said.isEmpty())
    }

    @Test
    fun `a reply is said with only his company name and how long ago`() = runBlocking {
        cursor.since = now - 15 * minute
        mail = MailCheck.Found(
            listOf(
                MailHeaders("Acme Talent <talent@ats.example>", "Interview: SECRET DETAILS", now - 4 * minute),
                MailHeaders("Someone <x@other.example>", "Unrelated", now - 2 * minute),
            ),
        )
        assertEquals(listOf("Acme"), check.run(now))
        assertEquals(listOf("Acme emailed you 4 minutes ago."), said)
        assertFalse(said.single().contains("SECRET") || said.single().contains("talent@"))
        assertTrue(queries.single().startsWith("after:${(now - 15 * minute) / 1000} -category:promotions"))
        assertEquals(now, cursor.since)
    }

    @Test
    fun `not now leaves the clock where it was, so the next check finds it again`() = runBlocking {
        cursor.since = now - 15 * minute
        mail = MailCheck.Found(listOf(MailHeaders("Acme <a@acme.example>", "Hi", now - minute)))
        delivery = WatchDelivery.RETRY
        check.run(now)
        assertEquals(now - 15 * minute, cursor.since)
    }

    @Test
    fun `no Gmail grant is cannot see, never no replies`() = runBlocking {
        cursor.since = now - 15 * minute
        mail = MailCheck.CannotSee(MailReplyWording.CANNOT_SEE)
        assertTrue(check.run(now).isEmpty())
        assertEquals(MailReplyWording.CANNOT_SEE, cursor.blind)
        assertTrue(said.isEmpty())
        assertTrue(MailReplyWording.CANNOT_SEE.contains("not the same as no replies"))
    }

    @Test
    fun `no mail_reply watch means no mail is read at all`() = runBlocking {
        companies = emptyList()
        cursor.since = now - minute
        check.run(now)
        assertTrue(queries.isEmpty())
        assertNull(cursor.since)
    }

    @Test
    fun `it reads mail at most every fifteen minutes`() = runBlocking {
        cursor.since = now - minute
        check.maybeRun()
        now += MailReplyCheck.INTERVAL_MS - 1
        check.maybeRun()
        assertEquals(1, queries.size)
    }

    @Test
    fun `several companies are one line`() {
        assertEquals(
            "Acme and Initech emailed you, the latest about an hour ago.",
            MailReplyWording.facts(listOf("Acme", "Initech"), 61 * minute),
        )
    }

    @Test
    fun `a subject that only mentions a company is never said as a reply from it`() {
        val list = listOf("Acme")
        assertEquals(
            MailReplyMatch.Match("Acme", false),
            MailReplyMatch.match(list, "A friend <f@mail.example>", "Acme news"),
        )
        assertEquals(
            "Mail mentioning Acme in its subject came in 4 minutes ago.",
            MailReplyWording.facts(emptyList(), listOf("Acme"), 4 * minute),
        )
    }
}
