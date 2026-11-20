package com.kevin.legion.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Times a model reads, written in the phone's local time by code (phone audit 2026-11-19: at 4:39 PM
 * local a reply said it was past ten at night, the local time plus the UTC offset it had been told).
 * A raw epoch or an ISO instant in UTC leaves the zone sum to the model; these never do. The UTC offset
 * is deliberately absent from what they write.
 */
object LocalClock {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val SPOKEN = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd, h:mm a", Locale.US)

    /** "2026-11-19 18:30 local" for a stored instant. */
    fun stamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(STAMP) + " local"

    /** [stamp] for an ISO-8601 instant from a service ("2026-11-20T00:30:00Z"); other text unchanged. */
    fun stamp(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching { stamp(Instant.parse(iso.trim()).toEpochMilli(), zone) }.getOrDefault(iso)

    /** One sentence with the local day and time now, for a sub-agent's instruction. */
    fun turnLine(now: ZonedDateTime = ZonedDateTime.now()): String =
        "It is now ${now.format(SPOKEN)}, his local time; every time here is local, never convert it."
}
