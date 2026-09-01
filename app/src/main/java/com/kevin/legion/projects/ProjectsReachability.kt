package com.kevin.legion.projects

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a projects lookup into the sentence the model is handed - pure logic, no Android types and
 * no network, so it is unit-testable. Same "pure builder, thin dispatch" split as
 * [com.kevin.legion.calendar.OpenerCalendarBriefing], and it exists for the same reason.
 *
 * **Why (`.scratch/dev-aspect/issues/07-the-staleness-contract.md`).** The projects surface answers
 * "what is pending" from two sources that fail in opposite ways, and both failures look like an
 * empty result unless something stops them:
 *
 *  - the LEGION board feed (`docs/board.json`, ticket 05) is a static file that can be days old
 *    while still parsing perfectly;
 *  - Azure DevOps (ticket 06) is read-through and live, but **a throttled request comes back HTTP
 *    200 with a `Retry-After` header rather than an error status** (traced to Microsoft in
 *    `.scratch/dev-aspect/research/azure-devops-api.md`), and a dead PAT is a 401 that a careless
 *    caller renders as "nothing assigned".
 *
 * Telling Kevin he is clear when the app cannot see is the failure. It is the same sentence as
 * telling him a receipt reconciled when the parser found nothing, and it is CLAUDE.md section 1's
 * unreadable-versus-empty rule, which cost an invented lunch appointment the last time it was got
 * wrong.
 *
 * **The ticket's table has three rows; the real shape is two axes**, and this class implements the
 * axes rather than the rows, because the row list silently omits a fourth case. Readability is one
 * axis (can the app see the source at all) and age is the other (how old is what it saw). A
 * **stale and empty** reading is the omitted combination, and it is the most dangerous one: "no
 * open work" spoken from a four-day-old file is a confident statement about today made from
 * evidence about last week. It gets the age clause exactly as a stale non-empty reading does.
 * Flagged here rather than silently reconciled - the ticket's table is the weaker statement of the
 * rule, and this file is the stronger one.
 *
 * A **live** source (Azure, read-through) passes `asOfMs = null` and can never be stale: there is
 * no cache to age. That is not a special case bolted on; it is ticket 02's read-through ruling
 * showing up as a type.
 */
object ProjectsReachability {

    /**
     * How old the board feed may be before its age is stated out loud. Twenty-four hours, written
     * down here rather than left to the model, because the commit hook regenerates the feed on
     * every commit - so a feed older than a day means Kevin has not committed for a day, which is
     * itself worth saying.
     */
    const val STALE_AFTER_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * Why a source could not be read. Every constant here produces a "cannot see" sentence, never
     * an empty one - that is the entire contract, and the enum exists so a new failure has to pick
     * a side rather than defaulting into silence.
     */
    enum class Unreadable(val phrase: String) {
        /** No attempt has been made yet. Distinct from a fetch that came back empty. */
        NEVER_FETCHED("it has not been checked yet"),

        /** No PAT entered, or none for this organisation. Ticket 02: the PAT is on-device only. */
        NO_CREDENTIAL("no access token has been set up for it"),

        /** HTTP 401. A PAT also dies on a 30-90 day Entra sign-in-recency clock, before expiry. */
        UNAUTHORIZED("the access token was rejected, so it may have expired"),

        /**
         * The throttle. Azure DevOps returns **HTTP 200** with `Retry-After` rather than 429, so a
         * caller branching on the status code alone reads a throttle as an empty result set AND
         * hammers through its own throttle - which gets Kevin emailed by his employer.
         */
        THROTTLED("the server is rate-limiting requests right now"),

        /** Offline, DNS, TLS, timeout, or a body that would not parse. */
        UNREACHABLE("it could not be reached"),

        /**
         * WIQL silently truncates at 20,000 rows with no error, so a result sitting exactly on the
         * cap cannot be trusted as a count. Refusing to speak it is the only honest option: a
         * silently-wrong number spoken confidently is the failure this whole map exists to prevent.
         */
        TRUNCATED("it returned more results than it can count reliably"),
    }

    /** What a lookup of one source produced. */
    sealed interface Reading {

        /** Human name of the source, as spoken: "the LEGION board", "Azure DevOps". */
        val source: String

        /** The app could not see this source. Never renders as an empty result. */
        data class CannotSee(
            override val source: String,
            val reason: Unreadable,
        ) : Reading

        /**
         * The app saw the source. [count] may legitimately be zero - an empty day is a real answer.
         *
         * [asOfMs] is when the data was produced, or `null` for a **live** read-through source,
         * which has no age. A live source is never stale; a cached one carrying a null timestamp
         * would be a bug this type cannot catch, so only ticket 06's client passes null.
         */
        data class Saw(
            override val source: String,
            val count: Int,
            val asOfMs: Long?,
        ) : Reading
    }

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE d MMMM 'at' h:mm a", Locale.US)

    /**
     * The sentence for one source. Model-facing, in the same register as
     * [com.kevin.legion.calendar.OpenerCalendarBriefing.NO_PERMISSION]: it states the fact and
     * forbids the wrong sentence, rather than trusting the model to infer the boundary.
     *
     * @param nowMs current wall clock, injected so the staleness branch is testable
     * @param zone the user's zone. Never an IANA id handed to the model (CLAUDE.md section 1) - it
     *   is used here only to FORMAT a timestamp, and the id itself never reaches the string.
     */
    fun describe(reading: Reading, nowMs: Long, zone: ZoneId): String = when (reading) {
        is Reading.CannotSee ->
            "You cannot see ${reading.source} right now - ${reading.reason.phrase}. Say NOTHING " +
                "about what is or is not pending there: not that there is work, and not that " +
                "there is none. You do not know. Say plainly that you cannot see it, and why."

        is Reading.Saw -> {
            val ageMs = reading.asOfMs?.let { nowMs - it }
            val stale = ageMs != null && ageMs >= STALE_AFTER_MS
            val body = if (reading.count == 0) {
                "${reading.source} has nothing open"
            } else {
                "${reading.source} has ${reading.count} open " +
                    if (reading.count == 1) "item" else "items"
            }
            if (!stale) {
                // Fresh, or live with no age at all. State it flatly: no hedge is warranted, and a
                // hedge on a good reading trains the listener to ignore the real ones.
                "$body, checked just now. State this as fact, and do not pad it with work you " +
                    "cannot see."
            } else {
                // The age goes in the SAME sentence as the count, not appended after it, so a
                // clipped or interrupted answer cannot drop the qualifier and keep the number.
                val stamp = Instant.ofEpochMilli(reading.asOfMs!!).atZone(zone).format(STAMP)
                "As of $stamp, which is ${ageInWords(ageMs!!)} old, $body. You MUST say how old " +
                    "that reading is in the same breath as the number - it may have changed since."
            }
        }
    }

    /**
     * Rounded, spoken-sounding age. Deliberately vague past a day: precision the source does not
     * warrant reads as confidence it has not earned, and nobody needs "49 hours".
     */
    fun ageInWords(ageMs: Long): String {
        val hours = ageMs / (60L * 60L * 1000L)
        val days = hours / 24L
        return when {
            hours < 48L -> "about a day"
            days < 7L -> "about $days days"
            days < 14L -> "over a week"
            days < 60L -> "about ${days / 7L} weeks"
            else -> "months"
        }
    }

    /**
     * Classifies an HTTP response BEFORE its body is trusted. Returns the reason it is unreadable,
     * or `null` when the response is genuinely usable.
     *
     * **The `Retry-After` check comes first, and that ordering is the point.** Azure DevOps signals
     * a throttle with a 200 carrying `Retry-After`; a classifier testing `status == 200` first
     * would hand a throttled response's body to the parser, get zero rows, and report "nothing
     * pending". Ticket 06's client must call this rather than branching on the status code itself.
     */
    fun classify(statusCode: Int, retryAfter: String?): Unreadable? = when {
        !retryAfter.isNullOrBlank() -> Unreadable.THROTTLED
        statusCode == 401 || statusCode == 403 -> Unreadable.UNAUTHORIZED
        statusCode == 429 -> Unreadable.THROTTLED
        statusCode in 200..299 -> null
        else -> Unreadable.UNREACHABLE
    }

    /**
     * The WIQL truncation cap. Microsoft caps a WIQL result at 20,000 ids and says nothing when it
     * does, so a result sitting exactly on the cap is indistinguishable from a real 20,000 and
     * cannot be spoken as a count.
     */
    const val WIQL_ID_CAP: Int = 20_000

    fun truncatedIfAtCap(idCount: Int): Unreadable? =
        if (idCount >= WIQL_ID_CAP) Unreadable.TRUNCATED else null
}
