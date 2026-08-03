package com.kevin.legion.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

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

    /**
     * The regression guard for the 2026-08-02 device finding: a receipt
     * printed 04/18/2026 rendered as "Apr 17, 2026" on a UTC-5 device, and
     * every ledger transaction date had been shifting the same way since it
     * shipped. Ingestion writes these at UTC midnight, so reading them back
     * in UTC is what round-trips the printed date.
     *
     * This test asserts the property that actually matters - the rendered
     * date equals the date that was parsed - rather than a fixed string, so
     * it holds whatever zone the test JVM runs in.
     */
    @Test
    fun `a document date round-trips the printed calendar date`() {
        for (iso in listOf("2026-04-18", "2026-01-01", "2026-12-31", "2026-03-01")) {
            val stored = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val expected = LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            assertEquals("printed $iso must render as printed", expected, documentDate(stored))
        }
    }

    @Test
    fun `a document date is identical regardless of the device timezone`() {
        val stored = LocalDate.parse("2026-04-18").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val original = TimeZone.getDefault()
        try {
            // UTC-11 and UTC+14 are the extremes; a local-zone renderer lands
            // on the 17th in one and the 19th in the other.
            for (zone in listOf("Pacific/Midway", "Pacific/Kiritimati", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("wrong in $zone", "Apr 18, 2026", documentDate(stored))
                assertEquals("wrong in $zone", "Apr 18", documentDateCompact(stored))
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
