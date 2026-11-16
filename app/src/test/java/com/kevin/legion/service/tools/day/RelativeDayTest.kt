package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
