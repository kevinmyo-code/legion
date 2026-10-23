package com.kevin.legion.brief

import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's parser against a body shaped exactly as `server/brief/views.py` emits it:
 * `BriefSerializer` is the brief object ITSELF (no `{"brief": ...}` wrapper), `sections` is a list
 * of `BriefSectionSerializer`, each line a `BriefLineSerializer` with `links` of
 * `BriefLinkSerializer`. DRF renders `built_at` in UTC (`TIME_ZONE = "UTC"`) with microseconds and a
 * `Z`, `brief_date` as `YYYY-MM-DD`, `id` as a UUID string, and `note` as JSON null when absent.
 * If the engine renames or wraps anything, this fails the build instead of a blank modal on the phone.
 */
class BriefWireContractTest {

    companion object {
        /** A body as `BriefSerializer(payload(brief, now)).data` renders it. */
        val BODY = """
            {"id": "6f1d2c3a-0a54-4c40-9a3b-0c1b7e9f2a11", "brief_date": "2026-10-22",
             "built_at": "2026-10-22T10:02:07.123456Z", "trigger": "scheduled",
             "age_sentence": "Built 3 hours ago, by the 05:00 build.", "news_ok": true,
             "notes": ["The household has no timezone set, so this brief uses UTC."],
             "sections": [
               {"key": "canvas", "title": "Canvas", "status": "ok",
                "lines": [{"text": "Thu Oct 23: Lab 4 report (MATH 2413), due 11:59 PM, not submitted.",
                           "unverified": false, "links": []}],
                "note": "Canvas last synced 2 days ago."},
               {"key": "money", "title": "Money", "status": "ok",
                "lines": [{"text": "Yesterday you spent ${'$'}42.10 across 3 merchants. Unverified: includes bank rows no statement gate has checked.",
                           "unverified": true, "links": []},
                          {"text": "Bills due in the next 7 days: LEGION has no source of recurring charges yet, so no bills are listed. This is not a sign that none are due.",
                           "unverified": false, "links": []}],
                "note": null},
               {"key": "car", "title": "Car", "status": "empty",
                "lines": [{"text": "No vehicle on file.", "unverified": false, "links": []}],
                "note": null},
               {"key": "news", "title": "News", "status": "ok",
                "lines": [{"text": "A lab released a new open-weights model.", "unverified": false,
                           "links": [{"title": "example.com", "url": "https://example.com/a"},
                                     {"title": "news.example.org", "url": "https://news.example.org/b"}]}],
                "note": null}
             ]}
        """.trimIndent()

        /** News failed: the section carries no lines and its `note` says why. */
        val FAILED_NEWS_BODY = BODY
            .replace(""""news_ok": true""", """"news_ok": false""")
            .replace(
                Regex("""\{"key": "news".*?"note": null\}""", RegexOption.DOT_MATCHES_ALL),
                """{"key": "news", "title": "News", "status": "failed", "lines": [],
                    "note": "News could not be built: the household has no Gemini key saved."}""",
            )
    }

    @Test
    fun `reads the brief object, its four sections in order, and the top-level fields`() {
        val brief = parseBrief(BODY)
        assertEquals("6f1d2c3a-0a54-4c40-9a3b-0c1b7e9f2a11", brief.id)
        assertEquals(LocalDate.of(2026, 10, 22), brief.briefDate)
        assertEquals(
            OffsetDateTime.parse("2026-10-22T10:02:07.123Z").toInstant().toEpochMilli(),
            brief.builtAtMs,
        )
        assertEquals(ServerBrief.TRIGGER_SCHEDULED, brief.trigger)
        assertEquals("Built 3 hours ago, by the 05:00 build.", brief.ageSentence)
        assertTrue(brief.newsOk)
        assertEquals(listOf("The household has no timezone set, so this brief uses UTC."), brief.notes)
        assertEquals(listOf("canvas", "money", "car", "news"), brief.sections.map { it.key })
        assertEquals(listOf("Canvas", "Money", "Car", "News"), brief.sections.map { it.title })
    }

    @Test
    fun `lines keep the server's words, the unverified flag, the note and the links`() {
        val brief = parseBrief(BODY)
        val canvas = brief.sections[0]
        assertEquals("Canvas last synced 2 days ago.", canvas.note)
        val money = brief.sections[1]
        assertNull(money.note)
        assertTrue(money.lines[0].unverified)
        assertTrue(money.lines[0].text.endsWith("Unverified: includes bank rows no statement gate has checked."))
        assertFalse(money.lines[1].unverified)
        val car = brief.sections[2]
        assertEquals(BriefSection.STATUS_EMPTY, car.status)
        assertEquals("No vehicle on file.", car.lines.single().text)
        val news = brief.sections[3].lines.single()
        assertEquals(
            listOf(
                BriefLink("example.com", "https://example.com/a"),
                BriefLink("news.example.org", "https://news.example.org/b"),
            ),
            news.links,
        )
    }

    @Test
    fun `a failed section has no lines and its sentence in the note`() {
        val brief = parseBrief(FAILED_NEWS_BODY)
        assertFalse(brief.newsOk)
        val news = brief.sections.last()
        assertTrue(news.failed)
        assertTrue(news.lines.isEmpty())
        assertEquals("News could not be built: the household has no Gemini key saved.", news.note)
    }

    @Test
    fun `a wrapped brief or a bare section list is refused, not read as an empty brief`() {
        assertThrows(IllegalArgumentException::class.java) { parseBrief("""{"brief": $BODY}""") }
        assertThrows(IllegalArgumentException::class.java) { parseBrief("[]") }
        assertThrows(IllegalArgumentException::class.java) {
            parseBrief(BODY.replace(""""sections"""", """"results""""))
        }
    }

    @Test
    fun `a missing required field is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBrief(BODY.replace(""""built_at"""", """"built""""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseBrief(BODY.replace(""""text": "No vehicle""", """"words": "No vehicle"""))
        }
    }

    @Test
    fun `the 404, 409, 503 and 429 bodies are read for their sentence, verbatim`() {
        val none = "No brief has been built for you yet. One is built at 05:00 each day, or press Refresh to " +
            "build one now. Nothing was read."
        assertEquals(none, briefDetail("""{"detail": "$none"}"""))
        assertEquals(
            "Request was throttled. Expected available in 1234 seconds.",
            briefDetail("""{"detail": "Request was throttled. Expected available in 1234 seconds."}"""),
        )
        assertNull(briefDetail("<html>Bad Gateway</html>"))
        assertNull(briefDetail("""{"sources": []}"""))
    }
}
