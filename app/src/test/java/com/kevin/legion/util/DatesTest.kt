package com.kevin.legion.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function coverage for [relativeAge] - ticket 09's FLEET LIVE block
 * needs it to say honestly how stale a reading is (nothing in the OBD stack
 * has run since the port). No Android dependency, plain JVM test.
 */
class DatesTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `under 45 seconds reads as just now`() {
        assertEquals("just now", relativeAge(now - 10_000, now))
        assertEquals("just now", relativeAge(now, now))
    }

    @Test
    fun `minutes bucket, singular and plural`() {
        assertEquals("1 minute ago", relativeAge(now - 60_000, now))
        assertEquals("5 minutes ago", relativeAge(now - 5 * 60_000, now))
    }

    @Test
    fun `hours bucket, singular and plural`() {
        assertEquals("1 hour ago", relativeAge(now - 60 * 60_000, now))
        assertEquals("3 hours ago", relativeAge(now - 3 * 60 * 60_000, now))
    }

    @Test
    fun `days bucket, singular and plural`() {
        assertEquals("1 day ago", relativeAge(now - 24 * 60 * 60_000, now))
        assertEquals("3 days ago", relativeAge(now - 3 * 24 * 60 * 60_000, now))
    }

    @Test
    fun `months bucket once the gap crosses 30 days`() {
        assertEquals("1 month ago", relativeAge(now - 31L * 24 * 60 * 60_000, now))
        assertEquals("2 months ago", relativeAge(now - 61L * 24 * 60 * 60_000, now))
    }

    @Test
    fun `a timestamp after now (clock skew) floors to just now rather than a negative age`() {
        assertEquals("just now", relativeAge(now + 60_000, now))
    }
}
