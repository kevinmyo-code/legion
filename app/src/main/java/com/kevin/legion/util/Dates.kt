package com.kevin.legion.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared date formatting so the same "MMM d, yyyy" rendering isn't reimplemented
 * in every screen, tool, and agent that stamps an epoch-millis timestamp.
 */
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val COMPACT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/** Formats an epoch-millis timestamp as a short local date, e.g. "Jun 29, 2026". */
fun shortDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(SHORT_DATE)

/**
 * No-year date for a row where space is at a premium and the year is
 * implied by context - ticket 08's ledger stream (resolution §4 fix 1) folds
 * the date into the transaction row rather than giving it its own gutter
 * column, e.g. "Jul 2".
 */
fun compactDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(COMPACT_DATE)

/**
 * A date PRINTED ON A DOCUMENT - a statement transaction date, a receipt's
 * purchase date - rendered exactly as printed.
 *
 * **Why these need their own formatter.** Every ingestion path normalises a
 * parsed calendar date to UTC midnight
 * (`LocalDate.parse(...).atStartOfDay(ZoneOffset.UTC)`): both statement
 * parsers, the statement agent, and the receipt agent. Rendering that through
 * [shortDate]/[compactDate] converts it to the DEVICE's zone, and anywhere
 * west of UTC that lands on the previous calendar day. A receipt printed
 * 04/18/2026 displayed as "Apr 17, 2026" on the A17K at UTC-5, and the ledger
 * had been doing the same to every transaction date since it shipped.
 *
 * These values are date-only by construction - there is no time of day on a
 * statement line - so the correct rendering reads them back in the same zone
 * they were written in, which round-trips the printed date exactly and is
 * identical on every device.
 *
 * **Do NOT use these for a real instant.** `CodeEvent.timestamp`,
 * `ServiceRecord.date` and `BuildEntry.date` are `System.currentTimeMillis()`
 * captures, and `MaintenanceItem.lastDoneDate` is normalised to LOCAL midnight
 * by `LiveToolbox.parseIsoDate`. All of those are correct through
 * [shortDate]/[compactDate] and would be wrong here.
 */
fun documentDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().format(SHORT_DATE)

/** [documentDate] without the year, for the ledger stream's folded date. See [compactDate]. */
fun documentDateCompact(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().format(COMPACT_DATE)

/**
 * "3 days ago" / "2 hours ago" / "just now" - ticket 09 resolution §1's FLEET
 * LIVE block: nothing in the OBD stack has run since the port, so every
 * reading shown is a PAST one, and the screen must say how stale it is rather
 * than let a bare number read as live. Pure function of two millis so it is
 * unit-testable without Robolectric, same posture as [com.kevin.legion.ledger.formatCents].
 *
 * Buckets widen as the gap grows (seconds -> minutes -> hours -> days ->
 * months) because a driver caring about staleness at the minute level past a
 * few days is not the failure mode this exists to catch - "47 days ago" reads
 * the same as "48 days ago" would, so once the value is that stale, more
 * precision buys nothing. A negative gap (clock skew, or a sample stamped a
 * moment after [now] is captured) floors to "just now" rather than printing a
 * nonsensical negative age.
 */
fun relativeAge(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val deltaMs = (now - epochMs).coerceAtLeast(0)
    val seconds = deltaMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    return when {
        seconds < 45 -> "just now"
        minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        days < 30 -> if (days == 1L) "1 day ago" else "$days days ago"
        else -> if (months <= 1L) "1 month ago" else "$months months ago"
    }
}
