package com.kevin.legion.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared date formatting so the same "MMM d, yyyy" rendering isn't reimplemented
 * in every screen, tool, and agent that stamps an epoch-millis timestamp.
 */
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Formats an epoch-millis timestamp as a short local date, e.g. "Jun 29, 2026". */
fun shortDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(SHORT_DATE)
