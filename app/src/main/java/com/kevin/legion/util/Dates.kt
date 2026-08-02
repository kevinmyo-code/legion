package com.kevin.legion.util

import java.time.Instant
import java.time.ZoneId
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
