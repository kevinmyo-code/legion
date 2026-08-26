package com.kevin.legion.ui

/**
 * What a screen knows about its own last read, and the words it owes the user because of it.
 *
 * **Why this exists.** Backend-erp phase 3 (`.scratch/backend-erp/issues/05-migration-path.md`).
 * Once reads are cache-first against a remote Postgres (ticket 01 ruling 9), a screen can be
 * showing figures that are correct, figures that are old, or figures it failed to refresh - and
 * today it has no way to say which. Every aspect screen has a `loading` boolean and nothing else:
 * no error channel at all, and no record of when its data was actually read.
 *
 * **This is deliberately Compose-free and pure**, following `ui/TodayGapResolvers.kt`, which is the
 * in-house precedent: a builder returns already-worded strings plus a severity flag, and the
 * composable picks a colour off the flag rather than re-deriving meaning from the string it is
 * about to print. That also means the staleness logic below is genuinely unit-tested even though
 * the app cannot trigger it yet - reads are still local and instant, so [loadedAtMs] is always
 * within moments of now. Shipping the rule with tests but no live trigger is the deliberate
 * alternative to shipping an untested half, which is exactly the failure the `DatabaseSnapshot`
 * restore turned out to be.
 *
 * **Two different "as of" facts exist and must not be conflated.** `AccountBalance.asOfMs`, already
 * rendered by `ui/ledger/LedgerRows.kt:196`, is the DOCUMENT's own date: the statement said this
 * balance was true on the 15th. [loadedAtMs] here is when THIS DEVICE last successfully read its
 * copy. A balance can be correctly "as of 15 Aug" while the cache holding it is three days
 * unrefreshed, and those are two true sentences about different things. This type never touches
 * the first.
 */
data class ReadState(
    /** True while a load is in flight AND nothing has ever loaded. See [isFirstLoad]. */
    val loading: Boolean = true,
    /**
     * When the last SUCCESSFUL load finished, or null if none ever has. Null and "loading" are not
     * the same: a screen that has failed every attempt since launch is not loading, and has no
     * data, and must say so rather than showing an eternal spinner.
     */
    val loadedAtMs: Long? = null,
    /**
     * The worded reason the most recent attempt failed, or null if it succeeded. Kevin's ruling,
     * 2026-08-26: on failure a screen KEEPS whatever it already had and says this alongside it,
     * rather than blanking working data over a transient blip. The corollary is that this line has
     * to be genuinely visible - a stale figure shown without it would be the app asserting
     * something it cannot vouch for.
     */
    val failure: String? = null,
) {
    /** Nothing has ever loaded, so there is no data behind whatever is on screen. */
    val isFirstLoad: Boolean get() = loadedAtMs == null

    /** A load succeeded at some point, so the figures on screen are real, if possibly old. */
    val hasData: Boolean get() = loadedAtMs != null
}

/**
 * A line a screen should print about its own freshness, or null when there is nothing honest to
 * say. [advisory] drives colour: false for a neutral note, true for something the user may need
 * to act on.
 */
data class ReadStateLine(val text: String, val advisory: Boolean)

/**
 * How old a cache may get before a screen mentions it. Reads are local today so this is never
 * reached in practice; it starts mattering the moment phase 4 puts a network between the screen
 * and the data.
 *
 * Ten minutes rather than seconds: the point is to catch "this has not refreshed in a while",
 * not to narrate every render.
 */
const val READ_STALE_AFTER_MS: Long = 10 * 60 * 1000L

/**
 * The one place that decides what a screen says about its own read.
 *
 * Order matters and is the whole design:
 *  1. **A failure with no data at all** is the loudest case - there is nothing on screen to trust.
 *  2. **A failure with data** keeps the data and says the refresh failed. Kevin's ruling.
 *  3. **Stale data** says how old it is, once past [staleAfterMs].
 *  4. **Fresh data says nothing.** Silence is the correct output for the normal case; a permanent
 *     "as of just now" would train the eye to skip exactly the line that matters later.
 *
 * [nowMs] is injected rather than read from the clock so this is testable, matching how
 * `TodayScreen` already takes one "now" per load.
 */
fun readStateLine(
    state: ReadState,
    nowMs: Long,
    staleAfterMs: Long = READ_STALE_AFTER_MS,
    formatAge: (Long) -> String = ::compactAge,
): ReadStateLine? {
    if (state.failure != null) {
        return if (state.isFirstLoad) {
            ReadStateLine("Couldn't load this. ${state.failure}", advisory = true)
        } else {
            ReadStateLine("Showing the last data read. Refresh failed: ${state.failure}", advisory = true)
        }
    }

    val loadedAt = state.loadedAtMs ?: return null
    val age = nowMs - loadedAt
    if (age >= staleAfterMs) {
        return ReadStateLine("Showing data read ${formatAge(age)} ago.", advisory = true)
    }
    return null
}

/**
 * A coarse, human age. Deliberately imprecise: "4 minutes" and "4 minutes 12 seconds" carry the
 * same decision, and the second one invites reading the number rather than the point.
 */
fun compactAge(ageMs: Long): String {
    val minutes = ageMs / 60_000L
    if (minutes < 60) return if (minutes <= 1) "a minute" else "$minutes minutes"
    val hours = minutes / 60
    if (hours < 24) return if (hours == 1L) "an hour" else "$hours hours"
    val days = hours / 24
    return if (days == 1L) "a day" else "$days days"
}
