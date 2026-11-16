package com.kevin.legion.ai

import com.kevin.legion.service.cascade.CascadePrompt
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date the voice model reasons from is the user's LOCAL date, at both edges of the night
 * (2026-11-15 voice eval: at about 19:00 at UTC-06:00, already Monday in UTC, flash-lite turned
 * "tomorrow" into today+2 and called Sunday "Monday"). Today, tomorrow and yesterday are spelled out,
 * so nothing is left to arithmetic, and the clock is a UTC offset, never a zone id.
 */
class LocalDatesTest {
    private val west = ZoneOffset.ofHours(-6)

    private fun at(day: LocalDate, hour: Int, minute: Int) = ZonedDateTime.of(day.atTime(hour, minute), west)

    @Test fun `at 23 30 local, already tomorrow in UTC, today is still the local day`() {
        val now = at(LocalDate.of(2026, 11, 15), 23, 30)
        assertEquals(LocalDate.of(2026, 11, 16), now.withZoneSameInstant(ZoneOffset.UTC).toLocalDate())
        val text = localDates(now)
        assertTrue(text, text.startsWith("Today's date is Sunday, November 15, 2026 (2026-11-15)"))
        assertTrue(text, text.contains("Tomorrow is Monday, November 16 (2026-11-16)"))
        assertTrue(text, text.contains("yesterday was Saturday, November 14 (2026-11-14)"))
        assertTrue(text, text.contains("never a UTC one"))
        val clock = clockNote(now)
        assertTrue(clock, clock.startsWith("Right now it is Sunday, November 15, 2026 at 11:30 PM"))
        assertTrue(clock, clock.contains("UTC-06:00"))
    }

    @Test fun `at 00 30 local the new day has begun`() {
        val now = at(LocalDate.of(2026, 11, 16), 0, 30)
        val text = localDates(now)
        assertTrue(text, text.startsWith("Today's date is Monday, November 16, 2026 (2026-11-16)"))
        assertTrue(text, text.contains("Tomorrow is Tuesday, November 17 (2026-11-17)"))
        assertTrue(text, text.contains("yesterday was Sunday, November 15 (2026-11-15)"))
        assertTrue(clockNote(now), clockNote(now).startsWith("Right now it is Monday, November 16, 2026 at 12:30 AM"))
    }

    @Test fun `the paragraph still splits out of the cached instruction, and names no zone id`() {
        val now = at(LocalDate.of(2026, 11, 15), 23, 30)
        val base = "An invented instruction.\n\n" + localDates(now) +
            " The user's clock reads UTC-06:00.\n\nMore rules."
        val split = CascadePrompt.split(base)
        assertTrue(split.volatile, split.volatile.contains("(2026-11-16)"))
        assertFalse(split.cacheable, split.cacheable.contains("2026-11-15"))
        val preamble = CascadePrompt.preamble(split.volatile, clockNote(now))
        assertTrue(preamble, preamble.contains("Today's date is Sunday, November 15, 2026 (2026-11-15)"))
        assertFalse(preamble, preamble.contains("America/"))
        assertEquals("+00:00", offsetText(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)))
    }
}
