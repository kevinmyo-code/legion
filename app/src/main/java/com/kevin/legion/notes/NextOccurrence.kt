package com.kevin.legion.notes

/**
 * Pure "what's the next occurrence at or after now" computation for a recurring [RepeatRule] - no
 * Android dependency, no Context, no Room, plain JVM unit test, same posture as [Recurrence]
 * itself. This is the function ticket 03's sharp edge and ticket 04's answer both point at: **boot
 * recovery must recompute the next occurrence forward from now, never resume from the last fired
 * occurrence** - if the phone was off when three occurrences of a daily series were due, the
 * correct next alarm is tomorrow's (or later today's), not a backlog of three.
 *
 * Built on top of [Recurrence.occurrencesInWindow] rather than duplicating its date maths -
 * [Recurrence] already generates ascending dates and already subtracts skips during expansion, so
 * the only new problem here is "how wide a window do I need to be sure I've found the very first
 * one". A too-narrow single window would silently miss a sparse `Yearly` rule whose one occurrence
 * this year already passed; widening geometrically (rather than picking one huge window) keeps the
 * common case (a daily or weekly reminder) cheap while still finding a yearly one.
 */
object NextOccurrence {
    /**
     * Search windows, in days, tried in order until one contains an occurrence. Doubles roughly
     * from a day out to ten years, which comfortably covers every rule [RepeatRule] can express:
     * the widest possible native period is [RepeatRule.Yearly] (one year), and even a
     * `MonthlyOnDate`/`Weekly`/`Daily` rule with a large `every` can only push its next occurrence
     * out by `every` * its base period, which ten years absorbs for any `every` a driver would
     * plausibly say aloud.
     */
    private val WINDOW_DAYS = longArrayOf(1, 7, 31, 92, 366, 731, 1_096, 1_827, 3_653)
    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * The earliest occurrence of [rule] (ending per [end], honouring [skippedDates]) that falls at
     * or after [now] - epoch ms, or null if the series has already ended (or no window tried finds
     * one, meaning the rule is malformed or effectively exhausted). [startsAt] is the series'
     * original first occurrence, same convention [Recurrence.occurrencesInWindow] uses.
     */
    fun compute(
        startsAt: Long,
        rule: RepeatRule,
        end: RepeatEnd,
        skippedDates: Set<Long>,
        now: Long,
        // Threaded straight through to Recurrence, which is where the day-maths
        // happens. Defaulting rather than hardcoding UTC is the 2026-08-07 audit
        // fix - see Recurrence.DEFAULT_ZONE for the wrong-day bug it closes.
        // This is the call that actually ARMS the alarm, so a zone mismatch here
        // is not a display error, it is a reminder firing on the wrong day.
        zone: java.time.ZoneId = Recurrence.DEFAULT_ZONE,
    ): Long? {
        for (days in WINDOW_DAYS) {
            val windowEnd = now + days * MILLIS_PER_DAY
            val occurrences =
                Recurrence.occurrencesInWindow(startsAt, rule, end, skippedDates, now, windowEnd, zone)
            if (occurrences.isNotEmpty()) return occurrences.first()
        }
        return null
    }
}
