package com.kevin.legion.brief

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's own sentences about the brief: when it was built, how old a saved copy is, and why a
 * read or a refresh did not happen. **Never a sentence about the brief's facts**: those are the
 * engine's words and are shown as they are. Pure, so every wording is pinned by a test.
 */
object BriefWords {
    private const val MS_PER_S = 1_000L
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val DAY_S = 86_400L
    private const val HOURS_BEFORE_DAYS = 48L
    private const val HTTP_CONFLICT = 409
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_UNAVAILABLE = 503

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayAndClock: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

    const val NO_ENGINE = "This phone is not signed in to an engine, so no brief could be read. " +
        "Sign in under Settings, Engine."

    /** "Built 5:02 AM today", "Built yesterday 5:01 AM", or "Built Mon Oct 20, 5:01 AM", in [zone]. */
    fun built(builtAtMs: Long, nowMs: Long, zone: ZoneId): String {
        val built = Instant.ofEpochMilli(builtAtMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (built.toLocalDate()) {
            today -> "Built ${clock.format(built)} today"
            today.minusDays(1) -> "Built yesterday ${clock.format(built)}"
            else -> "Built ${dayAndClock.format(built)}"
        }
    }

    /** "just now", "1 minute ago", "3 hours ago", "4 days ago": the server's own `ago` steps. */
    fun ago(thenMs: Long, nowMs: Long): String {
        val seconds = maxOf(0L, (nowMs - thenMs) / MS_PER_S)
        return when {
            seconds < MINUTE_S -> "just now"
            seconds < HOUR_S -> plural(seconds / MINUTE_S, "minute") + " ago"
            seconds < HOURS_BEFORE_DAYS * HOUR_S -> plural(seconds / HOUR_S, "hour") + " ago"
            else -> "${seconds / DAY_S} days ago"
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** The line above a cached brief: that it is the saved copy, how old the copy is, and why. */
    fun savedCopy(savedAtMs: Long, nowMs: Long, why: String): String =
        "This is the saved copy on this phone, saved ${ago(savedAtMs, nowMs)}, because a fresh one " +
            "could not be read. $why"

    /** Why `GET /api/brief/latest` did not give a brief. The engine's own words where it gave some. */
    fun readFailure(error: Throwable?): String = when (val failure = (error as? EngineHttpException)?.failure) {
        is EngineFailure.Refused ->
            "Couldn't read the brief: the engine answered HTTP ${failure.status}." +
                (briefDetail(failure.body)?.let { " $it" } ?: "")
        null -> "Couldn't read the brief: ${error?.message ?: "no reason was given"}."
        else -> failure.sentence
    }

    /**
     * Why a Refresh did not happen, in words. 409 and 503 are the engine's own sentences, which
     * already say that nothing changed. 429 is DRF's throttle sentence, so the phone says first
     * what did not happen. Everything else names the HTTP status or the transport failure.
     */
    fun refreshFailure(error: Throwable?): String = when (val failure = (error as? EngineHttpException)?.failure) {
        is EngineFailure.Refused -> {
            val detail = briefDetail(failure.body)
            when (failure.status) {
                HTTP_CONFLICT, HTTP_UNAVAILABLE ->
                    detail ?: "The brief could not be refreshed (HTTP ${failure.status}). Nothing changed."
                HTTP_TOO_MANY ->
                    "The engine limits how often a brief can be refreshed, so this refresh did not run " +
                        "and nothing changed." + (detail?.let { " $it" } ?: "")
                else ->
                    "The refresh did not run: the engine answered HTTP ${failure.status}. Nothing changed." +
                        (detail?.let { " $it" } ?: "")
            }
        }
        null -> "Couldn't refresh the brief: ${error?.message ?: "no reason was given"}. Nothing changed."
        else -> failure.sentence
    }
}
