package com.kevin.legion.gmail

import com.kevin.legion.ui.sync.GoogleGrantResolver
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure logic behind `search_mail`/`read_mail` (ticket 15, graduated from ticket 05's Answer),
 * factored out of [com.kevin.legion.service.LiveToolbox] so the briefing query, the cap rules,
 * and the four ticket-10 failure messages are plain JVM unit test targets - none of this touches
 * [GmailAuth], [GmailClient], Room, or Android, on purpose. Same posture as
 * [com.kevin.legion.ui.sync.GoogleGrantResolver]: this class is the one that can be wrong and
 * caught by a fast test, not the one that needs a device to exercise.
 */
object GmailToolLogic {

    /**
     * The app's own fixed briefing query (ticket 05's Answer, verbatim) - the model never writes
     * this one. `category:primary` does the "not promotions/social" filtering Gmail already
     * indexes for free; `newer_than:2d` covers a weekend from a Monday-morning ask.
     */
    const val BRIEFING_QUERY = "is:unread in:inbox category:primary newer_than:2d"

    /** Hard cap on a briefing (no query) - a SPOKEN limit, not a cost one (ticket 05: quota is a
     * non-constraint at ~405 units a briefing against 6,000/min). Past this, nobody is holding a
     * list that long in their head. */
    const val BRIEFING_CAP = 10

    /** Hard cap on a model-supplied search - a lookup, not a survey (ticket 05). */
    const val SEARCH_CAP = 5

    /** What `search_mail` actually runs, resolved from the model's raw `query`/`limit` arguments. */
    data class Plan(val query: String, val isBriefing: Boolean, val cap: Int)

    /**
     * A blank/omitted [query] IS the briefing: the app's own fixed [BRIEFING_QUERY] at
     * [BRIEFING_CAP], ignoring [limit] entirely - **the app decides the briefing, not the
     * model** (ticket 05's Answer: "a model choosing what to omit is a model deciding what
     * Kevin never hears about"). A non-blank [query] passes straight to Gmail's `q` UNCHANGED,
     * capped at [SEARCH_CAP] regardless of what [limit] asks for - the model may ask for
     * *fewer* results, never more than the hard cap.
     */
    fun plan(query: String?, limit: Int?): Plan {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Plan(query = BRIEFING_QUERY, isBriefing = true, cap = BRIEFING_CAP)
        }
        val requested = limit?.takeIf { it > 0 } ?: SEARCH_CAP
        return Plan(query = trimmed, isBriefing = false, cap = requested.coerceIn(1, SEARCH_CAP))
    }

    /**
     * A relative day label for a message's [timestampMs] against [now] - "today"/"yesterday"/
     * "N days ago", falling back to [com.kevin.legion.util.shortDate] once it's more than a week
     * old. Ticket 05's Answer names "this morning"/"yesterday" as illustrative phrasing, not a
     * verbatim-fixed string the way the two tool descriptions are (only those are marked FIXED);
     * this is the reasoned, testable reading of it - a day-granularity label rather than a
     * time-of-day one, since Gmail's `internalDate` gives no cheaper way to say "this morning"
     * that wouldn't just be restating the clock hour.
     */
    fun relativeMailDate(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return "unknown date"
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val daysBetween = ChronoUnit.DAYS.between(day, today)
        return when {
            daysBetween <= 0L -> "today"
            daysBetween == 1L -> "yesterday"
            daysBetween < 7L -> "$daysBetween days ago"
            else -> com.kevin.legion.util.shortDate(timestampMs)
        }
    }

    /** Which of ticket 10's four causes a failed mail tool call falls under. */
    enum class Cause { NO_NETWORK, NEEDS_CONSENT, NEVER_GRANTED, API_ERROR }

    const val NO_NETWORK_MESSAGE = "I can't reach Gmail - no connection."
    const val NEVER_GRANTED_MESSAGE = "You haven't given me access to Gmail yet."
    const val API_ERROR_MESSAGE = "Gmail returned an error - I'll not guess at what's in there."

    /**
     * [GmailAuth.authorize] returning `NeedsConsent` is one outcome for two different real
     * situations - a driver who has never once completed the consent round trip, and one whose
     * grant lapsed (7-day Testing-status expiry) or was revoked - and only
     * [com.kevin.legion.ai.CompanionProfile.isGmailEnabled] (whether this device EVER finished
     * that round trip before) can tell them apart, the same distinction
     * [com.kevin.legion.ui.sync.GoogleAccessScreen] already draws for Drive.
     */
    fun causeForNeedsConsent(everGranted: Boolean): Cause =
        if (everGranted) Cause.NEEDS_CONSENT else Cause.NEVER_GRANTED

    /**
     * A real (non-consent) failure is either a plain network failure that never reached Google,
     * or an actual API-side error (quota, malformed request, a revoked token discovered only at
     * the REST call) - see [GmailClient.FetchResult.networkFailure].
     */
    fun causeForFailure(isNetworkException: Boolean): Cause =
        if (isNetworkException) Cause.NO_NETWORK else Cause.API_ERROR

    /** The exact line Alfred says for [cause] - verbatim per ticket 10's Answer table. */
    fun message(cause: Cause): String = when (cause) {
        Cause.NO_NETWORK -> NO_NETWORK_MESSAGE
        Cause.NEEDS_CONSENT -> GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.GMAIL)
        Cause.NEVER_GRANTED -> NEVER_GRANTED_MESSAGE
        Cause.API_ERROR -> API_ERROR_MESSAGE
    }
}
