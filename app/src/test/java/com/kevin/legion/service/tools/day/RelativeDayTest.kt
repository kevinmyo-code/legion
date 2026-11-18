@file:Suppress("MaxLineLength") // test fixtures and phrase lists read best on one line

package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words a date tool takes resolve on the LOCAL date, at both edges of the night (2026-11-16). */
class RelativeDayTest {
    private val sunday = LocalDate.of(2026, 11, 15)

    @Test fun `words and iso dates resolve, anything else is refused`() {
        assertEquals(sunday, RelativeDay.parse("today", sunday))
        assertEquals(LocalDate.of(2026, 11, 16), RelativeDay.parse(" Tomorrow ", sunday))
        assertEquals(LocalDate.of(2026, 11, 14), RelativeDay.parse("yesterday", sunday))
        assertEquals(LocalDate.of(2026, 11, 20), RelativeDay.parse("2026-11-20", sunday))
        assertNull(RelativeDay.parse("next week", sunday))
        assertNull(RelativeDay.parse("", sunday))
    }

    @Test fun `at 23 30 local, already the next day in UTC, tomorrow is the local tomorrow`() {
        val west = ZoneOffset.ofHours(-6)
        val now = LocalDateTime.of(2026, 11, 15, 23, 30).atZone(west)
        assertEquals(LocalDate.of(2026, 11, 16), now.withZoneSameInstant(ZoneOffset.UTC).toLocalDate())
        val today = now.toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 16), RelativeDay.parse("tomorrow", today))
        val afterMidnight = LocalDateTime.of(2026, 11, 16, 0, 30).atZone(west).toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 17), RelativeDay.parse("tomorrow", afterMidnight))
    }

    @Test fun `a resolved day is said back with its weekday`() {
        assertEquals("Monday 2026-11-16", RelativeDay.label(LocalDate.of(2026, 11, 16)))
    }

    // ---- his own word wins over the model's arithmetic (2026-11-17 evening eval) -------------------

    private val tue = LocalDate.of(2026, 11, 17)

    @Test fun `the one day word he said is the day he meant`() {
        assertEquals(tue.plusDays(1), RelativeDay.saidDay("and tomorrow?", tue))
        assertEquals(tue.plusDays(1), RelativeDay.saidDay("What about tomorrow", tue))
        assertEquals(tue, RelativeDay.saidDay("what do I have due today", tue))
        assertEquals(tue, RelativeDay.saidDay("anything tonight?", tue))
    }

    @Test fun `anything more than one plain day word is left to the model`() {
        for (s in listOf(
            "the day after tomorrow", "tomorrow and the next day", "tomorrow or Friday", "this week",
            "tomorrow, the 19th", "what's due next Tuesday", "today and tomorrow", "", null,
            // The review's cases: another day named beside the day word.
            "tomorrow and Thanksgiving", "not tomorrow, the twenty first", "in two days, not tomorrow",
            "a fortnight from today", "move the dentist to tomorrow", "what did I miss yesterday",
        )) {
            assertNull(s, RelativeDay.saidDay(s, tue))
        }
    }

    @Test fun `a date one day off the day he said becomes that day, in any date argument, with a note`() {
        val tomorrow = tue.plusDays(1)
        fun fix(args: String, words: String = "and tomorrow?") = RelativeDay.corrected(org.json.JSONObject(args), words, tue)
        val read = fix("""{"from":"${tue.plusDays(2)}","to":"${tue.plusDays(2)}"}""")!!
        assertEquals(tomorrow.toString(), read.first.getString("from"))
        assertEquals(tomorrow.toString(), read.first.getString("to"))
        assertTrue(read.second.contains(tomorrow.toString()))
        // A write's single date moves too; other fields are untouched.
        val write = fix("""{"action":"add","date":"$tue","text":"invented"}""", "remind me tomorrow")!!.first
        assertEquals(tomorrow.toString(), write.getString("date"))
        assertEquals("invented", write.getString("text"))
        assertEquals(tomorrow.toString(), fix("""{"day":"${tue.plusDays(2)}"}""", "tick it off for tomorrow")!!.first.getString("day"))
        // Kept: the right day, two days off, a range, a word, nothing said.
        assertNull(fix("""{"date":"$tomorrow"}"""))
        assertNull(fix("""{"date":"${tue.plusDays(3)}"}"""))
        assertNull(fix("""{"from":"$tue","to":"${tue.plusDays(6)}"}"""))
        assertNull(fix("""{"date":"tomorrow"}"""))
        assertNull(fix("""{"date":"${tue.plusDays(2)}"}""", "what about Thursday"))
    }
}
