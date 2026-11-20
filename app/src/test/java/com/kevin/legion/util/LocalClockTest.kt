package com.kevin.legion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Times the model reads are local, written by code (phone audit 2026-11-19: at 4:39 PM local the reply
 * said it was past ten at night, the local time plus the stated UTC offset). Checked at a UTC/local
 * date boundary: 6:30 PM on a Thursday at UTC-6 is 00:30 on Friday in UTC. Made-up times.
 */
class LocalClockTest {
    private val zone = ZoneOffset.ofHours(-6)
    private val evening = ZonedDateTime.of(2026, 11, 19, 18, 30, 0, 0, zone)

    @Test
    fun `the turn line says the local day and time, never the UTC ones`() {
        val line = LocalClock.turnLine(evening)
        assertTrue(line, line.contains("Thursday"))
        assertTrue(line, line.contains("6:30 PM"))
        assertTrue(line, line.contains("local"))
        assertFalse(line, line.contains("Friday"))
        assertFalse(line, line.contains("12:30"))
        assertFalse(line, line.contains("UTC"))
    }

    @Test
    fun `a stored instant is stamped in local time`() {
        assertEquals("2026-11-19 18:30 local", LocalClock.stamp(evening.toInstant().toEpochMilli(), zone))
    }

    @Test
    fun `an ISO instant in UTC from a service is stamped in local time`() {
        assertEquals("2026-11-19 18:30 local", LocalClock.stamp("2026-11-20T00:30:00.000Z", zone))
    }

    @Test
    fun `text that is not an instant is passed through unchanged`() {
        assertEquals("", LocalClock.stamp("", zone))
        assertEquals("soon", LocalClock.stamp("soon", zone))
    }
}
