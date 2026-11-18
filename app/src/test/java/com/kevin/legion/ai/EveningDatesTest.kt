package com.kevin.legion.ai

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At 21:00 Central (already the next day in UTC) every date the prompt hands the model is his local
 * date, and nothing names the UTC day (2026-11-17: "and tomorrow?" read the day after tomorrow in the
 * evening). The read itself is guarded in code too (`RelativeDay.saidDay`).
 */
class EveningDatesTest {
    private val evening = LocalDateTime.of(2026, 11, 17, 21, 0).atZone(ZoneOffset.ofHours(-6))

    @Test
    fun `the date paragraph and the clock line name only his local days`() {
        val text = localDates(evening) + " " + clockNote(evening)
        val dates = Regex("""\d{4}-\d{2}-\d{2}""").findAll(text).map { it.value }.toSet()
        assertEquals(setOf("2026-11-16", "2026-11-17", "2026-11-18"), dates)
        assertTrue(text.contains("Today's date is Tuesday"))
        assertTrue(text.contains("9:00 PM"))
        // No UTC timestamp, and the UTC day (Wednesday 2026-11-18 03:00) is never "today".
        assertFalse(Regex("""\d{2}:\d{2}(:\d{2})?Z|\+00:00""").containsMatchIn(text))
        assertFalse(text.contains("Today's date is Wednesday"))
    }
}
