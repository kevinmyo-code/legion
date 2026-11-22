@file:Suppress("MaxLineLength") // fixtures and phrase lists read best on one line

package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-22: after a read of tomorrow, a follow-up add that named no day went on today. A dated
 * write that names no day goes on the day the conversation is about. Names are invented.
 */
class DateCarryTest {
    private val today = LocalDate.of(2027, 3, 9)
    private val tomorrow = today.plusDays(1)

    /** The filmed shape: the turn's second date call (a refused try came first). */
    private fun turn(about: LocalDate?) = RelativeDay.TurnDates(today, LocalTime.of(10, 16), onlyDateCall = false, aboutDay = about)

    private fun add(date: String) = JSONObject().put("action", "add").put("kind", "appointment")
        .put("item", "Dinner with Lee").put("date", date).put("time", "19:00")

    @Test
    fun `an add on today with no day said goes on the conversation's day`() {
        val (fixed, note) = DateCarry.carried("manage_item", add(today.toString()), "Never mind. Put dinner with Lee at 7:00.", turn(tomorrow))!!
        assertEquals(tomorrow.toString(), fixed.getString("date"))
        assertEquals("19:00", fixed.getString("time"))
        assertTrue(note, note.contains("Wednesday 2027-03-10"))
        // The resolved chain reaches it too, on a call that is not the turn's only date call.
        assertEquals(tomorrow.toString(), DateCarry.resolved("manage_item", add("today"), "put dinner with Lee at seven", turn(tomorrow))!!.first.getString("date"))
    }

    @Test
    fun `a day he names, a day the model did not put on today, or no other day, changes nothing`() {
        for (words in listOf("put dinner with Lee at seven tonight", "put dinner with Lee today at 7", "dinner with Lee on Friday at 7", "dinner with Lee on the 12th", "dinner with Lee this evening")) {
            assertNull(words, DateCarry.carried("manage_item", add(today.toString()), words, turn(tomorrow)))
        }
        assertNull(DateCarry.carried("manage_item", add("2027-03-12"), "put dinner with Lee at seven", turn(tomorrow)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), "put dinner with Lee at seven", turn(today)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), "put dinner with Lee at seven", turn(null)))
        assertNull(DateCarry.carried("manage_item", add(today.toString()), null, turn(tomorrow)))
        val tick = JSONObject().put("action", "tick").put("item", "dinner").put("date", today.toString())
        assertNull(DateCarry.carried("manage_item", tick, "tick dinner", turn(tomorrow)))
        assertNull(DateCarry.carried("read_calendar", JSONObject().put("from", "today").put("to", "today"), "what's on", turn(tomorrow)))
    }

    @Test
    fun `a clock time is not a day, a date is`() {
        assertTrue(DateCarry.namesNoDay("put coffee with Kim at 7:00"))
        assertTrue(DateCarry.namesNoDay("add lunch at half past twelve"))
        assertFalse(DateCarry.namesNoDay("add lunch tomorrow"))
        assertFalse(DateCarry.namesNoDay("add lunch next week"))
        assertFalse(DateCarry.namesNoDay("add lunch on March 3rd"))
        assertFalse(DateCarry.namesNoDay("add lunch on the twenty third"))
        assertFalse(DateCarry.namesNoDay(""))
    }

    @Test
    fun `a one-day read or a dated write sets the day, a range clears it, a failure keeps it`() {
        val oneDay = JSONObject().put("from", "tomorrow").put("to", "tomorrow")
        assertEquals(tomorrow, DateCarry.nextAboutDay(null, "read_calendar", oneDay, ok = true, today = today))
        assertEquals(today, DateCarry.nextAboutDay(tomorrow, "read_calendar", JSONObject(), ok = true, today = today))
        val week = JSONObject().put("from", today.toString()).put("to", today.plusDays(6).toString())
        assertNull(DateCarry.nextAboutDay(tomorrow, "read_calendar", week, ok = true, today = today))
        assertEquals(tomorrow, DateCarry.nextAboutDay(tomorrow, "read_calendar", week, ok = false, today = today))
        assertEquals(LocalDate.of(2027, 3, 12), DateCarry.nextAboutDay(tomorrow, "manage_item", add("2027-03-12"), ok = true, today = today))
        val undated = JSONObject().put("action", "add").put("item", "stamps")
        assertEquals(tomorrow, DateCarry.nextAboutDay(tomorrow, "manage_item", undated, ok = true, today = today))
        assertEquals(tomorrow, DateCarry.nextAboutDay(tomorrow, "get_weather", JSONObject(), ok = true, today = today))
    }
}
