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

    private val evening = java.time.LocalTime.of(21, 0)

    private fun fix(args: String, words: String = "and tomorrow?", tool: String = "read_calendar", day: LocalDate = tue, at: java.time.LocalTime = evening, only: Boolean = true) =
        RelativeDay.corrected(tool, org.json.JSONObject(args), words, RelativeDay.TurnDates(day, at, only))

    @Test fun `a single date one day off the day he said becomes that day, with a note`() {
        val tomorrow = tue.plusDays(1)
        val read = fix("""{"from":"${tue.plusDays(2)}","to":"${tue.plusDays(2)}"}""")!!
        assertEquals(tomorrow.toString(), read.first.getString("from"))
        assertEquals(tomorrow.toString(), read.first.getString("to"))
        assertTrue(read.second.contains(tomorrow.toString()))
        // A task or reminder-item write's one date moves too; other fields are untouched.
        val write = fix("""{"action":"schedule","date":"$tue","item":"invented"}""", "remind me tomorrow", tool = "manage_item")!!.first
        assertEquals(tomorrow.toString(), write.getString("date"))
        assertEquals("invented", write.getString("item"))
        assertEquals(tomorrow.toString(), fix("""{"date":"${tue.plusDays(2)}"}""", "remind me tomorrow", tool = "manage_item")!!.first.getString("date"))
        // Kept: the right day, two days off, a word, another day named.
        assertNull(fix("""{"from":"$tomorrow","to":"$tomorrow"}"""))
        assertNull(fix("""{"date":"${tue.plusDays(3)}"}""", tool = "manage_item"))
        assertNull(fix("""{"date":"tomorrow"}""", tool = "manage_item"))
        assertNull(fix("""{"from":"${tue.plusDays(2)}","to":"${tue.plusDays(2)}"}""", "what about Thursday"))
    }

    @Test fun `never a range, one side of one, another tool, or a turn with two date calls`() {
        val off = tue.plusDays(2)
        assertNull("a range", fix("""{"from":"$tue","to":"${tue.plusDays(6)}"}"""))
        assertNull("from today to the word", fix("""{"from":"$tue","to":"tomorrow"}"""))
        assertNull("only from", fix("""{"from":"$off"}"""))
        assertNull("only to", fix("""{"to":"$off"}"""))
        assertNull("date beside a range", fix("""{"date":"$off","from":"$off","to":"$off"}"""))
        assertNull("not a calendar or reminder tool", fix("""{"date":"$off"}""", tool = "log_sleep"))
        assertNull("a tick's date is not the evening bug", fix("""{"date":"$off"}""", "tick off the essay for tomorrow", tool = "complete_task"))
        assertNull("not the turn's only date call", fix("""{"from":"$off","to":"$off"}""", only = false))
        assertTrue(RelativeDay.isDateCall("manage_item", org.json.JSONObject("""{"date":"$off"}""")))
        assertTrue(!RelativeDay.isDateCall("manage_item", org.json.JSONObject("""{"item":"x"}""")))
        assertTrue("a tick still counts as a second date call", RelativeDay.isDateCall("complete_task", org.json.JSONObject("""{"date":"$off"}""")))
        assertTrue(!RelativeDay.isDateCall("money", org.json.JSONObject("""{"date":"$off"}""")))
    }

    @Test fun `the day is the turn's, so a call landing after midnight keeps a right tomorrow`() {
        // 23:59:58 on the 17th: "remind me tomorrow", the model rightly sends the 18th; the call runs at
        // 00:00:02 on the 18th. The turn's day is the 17th, so nothing moves.
        val late = java.time.LocalTime.of(23, 59, 58)
        assertNull(fix("""{"date":"${tue.plusDays(1)}"}""", "remind me tomorrow", tool = "manage_item", at = late))
        // A turn that STARTED at 00:00:02 on the 18th: tomorrow is the 19th; the 18th sent is one off.
        val early = java.time.LocalTime.of(0, 0, 2)
        val moved = fix("""{"date":"${tue.plusDays(1)}"}""", "remind me tomorrow", tool = "manage_item", day = tue.plusDays(1), at = early)!!
        assertEquals(tue.plusDays(2).toString(), moved.first.getString("date"))
    }

    @Test fun `tonight in the small hours is left to the model`() {
        val halfPastMidnight = java.time.LocalTime.of(0, 30)
        // At 00:30 on the 18th "tonight" may be the night in progress (the 17th's): nothing moves.
        assertNull(fix("""{"from":"$tue","to":"$tue"}""", "what's on tonight", day = tue.plusDays(1), at = halfPastMidnight))
        assertNull(RelativeDay.saidDay("what's on tonight", tue, halfPastMidnight))
        assertEquals(tue, RelativeDay.saidDay("what's on tonight", tue, evening))
    }
}
