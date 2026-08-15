package com.kevin.legion.notes

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.DayOfWeek

/**
 * A small hand-rolled repeat rule set for a recurring [com.kevin.legion.data.local.ListItem] -
 * explicitly NOT RFC 5545 (`.scratch/notes-lists-calendar/issues/04-recurrence-model.md`).
 * **Not supported, deliberately**: "last Friday of the month", "every 3rd Tuesday", "every 6
 * weeks" phrased as anything other than [Weekly]. Stored on [ListItem] as discrete columns
 * (`repeatKind` names one of these, the rest of the fields live in sibling columns) rather than
 * an encoded blob, so a repeat is inspectable in the schema and in a query - see
 * [com.kevin.legion.data.local.ListItem]'s doc comment.
 */
sealed class RepeatRule {
    /** Every [every] day(s) starting from the item's `startsAt` date. */
    data class Daily(val every: Int) : RepeatRule()

    /** Every [every] week(s), on each weekday in [days] (never empty - see [Recurrence]). */
    data class Weekly(val every: Int, val days: Set<DayOfWeek>) : RepeatRule()

    /**
     * Every [every] month(s), on [day]. **`MonthlyOnDate(every, 31)` in a 30-day month fires on
     * the last day of that month, not the 1st of the next** - ticket 04's named edge case, handled
     * by clamping rather than rolling over.
     */
    data class MonthlyOnDate(val every: Int, val day: Int) : RepeatRule()

    /**
     * Once a year, on [month]/[day]. No `every` - a yearly repeat is always every single year.
     * **`Yearly(2, 29)` in a common year fires on Feb 28**, the same clamp-not-roll-over rule as
     * [MonthlyOnDate].
     */
    data class Yearly(val month: Int, val day: Int) : RepeatRule()
}

/** How a series ends. Stored as `repeatEndKind` + the relevant one of `repeatEndDate`/`repeatEndCount`. */
sealed class RepeatEnd {
    object Never : RepeatEnd()

    /** The last valid occurrence is the last one on or before this date, at midnight in the caller's zone (see [Recurrence.DEFAULT_ZONE]). */
    data class OnDate(val dateEpochMillis: Long) : RepeatEnd()

    /** Only the first [n] occurrences of the series exist, ever - independent of any query window. */
    data class AfterCount(val n: Int) : RepeatEnd()
}

/**
 * Pure occurrence generator for [RepeatRule] - no Android dependency, no Context, no Room, so
 * this is a plain JVM unit test. This is the highest-risk correctness surface in the notes/lists/
 * calendar domain (`.scratch/notes-lists-calendar/issues/04-recurrence-model.md`): **occurrences
 * are computed on read, never materialised**, so a bug here silently changes what every calendar
 * view and every "what's next" answer ever says, with no stored row to catch it against.
 *
 * A recurring item can never be ticked (ticket 04), so there is no per-occurrence completion
 * state to reconcile against here - the only per-occurrence fact this generator has to honour is
 * a skip, which is subtracted DURING expansion (inside this function's own loop), never filtered
 * from the returned list afterward. That distinction matters: an "after count" series must count
 * a skipped occurrence toward its total just as if it had fired, or skipping would silently
 * extend a series past the number of times the driver actually asked for it.
 */
object Recurrence {
    /** Hard iteration cap so a malformed rule (e.g. `every = 0`) degrades to an empty-ish result rather than hanging. */
    private const val SAFETY_CAP = 100_000

    /**
     * **Every date calculation here happens in a caller-supplied zone, and that
     * is a bug fix, not a nicety (2026-08-07 audit).**
     *
     * This object used to pin all of its day-maths to `ZoneOffset.UTC` while
     * `ListItem.startsAt` is written as a device-zone instant by
     * `ui/notes/ListDetailScreen`. The two disagree whenever the device's UTC
     * offset pushes an instant across the UTC calendar-day boundary relative to
     * the local one - and the consequence was not a rounding error, it was a
     * whole series landing on the wrong day, permanently, while the UI
     * confidently displayed the day the driver had asked for.
     *
     * Traced example, Asia/Tokyo (UTC+9): a `Weekly(MONDAY)` reminder for
     * Monday 00:30 local is `Sunday 15:30Z`, so the UTC-anchored day-of-week
     * anchor read Sunday, advanced a week, and produced occurrences every
     * TUESDAY 00:30 local. West of UTC it shifts the other way: a Monday
     * 23:30 America/Los_Angeles series fires on Sundays. `Daily` was the only
     * rule that escaped, because it counts days rather than anchoring to a
     * day-of-week or day-of-month - which is exactly why the existing tests
     * never caught it. Every fixture in `RecurrenceTest` was built with
     * `atStartOfDay(ZoneOffset.UTC)`, so the suite never left UTC and a
     * UTC-vs-device-zone mismatch was invisible to it by construction.
     *
     * Tests pass an explicit zone (usually UTC) so they stay deterministic on
     * any machine; production passes the device zone. The parameter exists so
     * the assumption is stated at every call site instead of buried here.
     */
    val DEFAULT_ZONE: ZoneId get() = ZoneId.systemDefault()

    /**
     * Every occurrence of [rule] (ending per [end]) that falls within `[windowStart, windowEnd]`
     * (inclusive, epoch ms), skipping any date present in [skippedDates] (midnight-in-[zone] epoch ms,
     * matching [com.kevin.legion.data.local.ListItemSkip.skippedDate]'s convention), given the
     * series' first occurrence at [startsAt] (epoch ms - its time-of-day, if any, is preserved on
     * every later occurrence; an all-day item should pass a midnight-in-[zone] [startsAt]).
     *
     * **[zone] is load-bearing, not cosmetic** - see [DEFAULT_ZONE]'s doc comment for the bug that
     * made it a parameter. A caller storing a device-zone `startsAt` must pass the device zone.
     *
     * Returns occurrence start times in ascending order. Never throws on a malformed rule
     * (`every <= 0`, an empty [RepeatRule.Weekly.days]) - both degrade to an empty result, since a
     * rule that can never legitimately advance has no real occurrences to report.
     */
    fun occurrencesInWindow(
        startsAt: Long,
        rule: RepeatRule,
        end: RepeatEnd,
        skippedDates: Set<Long>,
        windowStart: Long,
        windowEnd: Long,
        zone: ZoneId = DEFAULT_ZONE,
    ): List<Long> {
        if (windowEnd < windowStart) return emptyList()
        if (!ruleIsWellFormed(rule)) return emptyList()

        // The time-of-day is carried as a LOCAL WALL-CLOCK time, not as a
        // millisecond offset from midnight (audit fix, 2026-08-07). An offset is
        // wrong across a DST boundary: on a spring-forward date, local midnight
        // to 07:00 is only six hours, so adding a fixed seven-hour offset landed
        // the occurrence at 08:00 and every daily reminder silently shifted an
        // hour for half the year. Recomposing `date + LocalTime` in the zone
        // keeps the wall-clock hour the driver actually asked for, which is what
        // "7am every day" means to a human.
        val startZoned = Instant.ofEpochMilli(startsAt).atZone(zone)
        val startDate = startZoned.toLocalDate()
        val timeOfDay: LocalTime = startZoned.toLocalTime()
        val endDateCutoff = (end as? RepeatEnd.OnDate)?.let { epochToDate(it.dateEpochMillis, zone) }
        val windowEndDate = epochToDate(windowEnd, zone)
        val maxCount = (end as? RepeatEnd.AfterCount)?.n

        val result = mutableListOf<Long>()
        var index = 0
        var iterations = 0
        for (date in candidateDates(rule, startDate)) {
            iterations++
            if (iterations > SAFETY_CAP) break
            if (maxCount != null && index >= maxCount) break
            if (endDateCutoff != null && date.isAfter(endDateCutoff)) break
            if (date.isAfter(windowEndDate)) break

            index++ // counts even a skipped occurrence - see this object's doc comment.
            val dateEpoch = dateToEpoch(date, zone)
            // atZone(ZoneId) resolves a local time that does not exist on a
            // spring-forward date (e.g. 02:30) forward to the first valid
            // instant rather than throwing or silently landing an hour out.
            val occurrenceEpoch = date.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
            if (occurrenceEpoch in windowStart..windowEnd && dateEpoch !in skippedDates) {
                result.add(occurrenceEpoch)
            }
        }
        return result
    }

    /** `every <= 0` or an empty weekly day set can never legitimately advance - see the function doc comment. */
    private fun ruleIsWellFormed(rule: RepeatRule): Boolean = when (rule) {
        is RepeatRule.Daily -> rule.every >= 1
        is RepeatRule.Weekly -> rule.every >= 1 && rule.days.isNotEmpty()
        is RepeatRule.MonthlyOnDate -> rule.every >= 1 && rule.day in 1..31
        is RepeatRule.Yearly -> rule.month in 1..12 && rule.day in 1..31
    }

    /**
     * Ascending dates on/after [startDate] where [rule] fires, lazily. Each branch only yields a
     * date `>= startDate` - a rule whose raw formula would land before the series began (e.g. a
     * `MonthlyOnDate` day earlier in the month than `startDate`'s own day) silently advances to
     * the next cycle instead, exactly like a real calendar app's "starts from today" behaviour.
     */
    private fun candidateDates(rule: RepeatRule, startDate: LocalDate): Sequence<LocalDate> = when (rule) {
        is RepeatRule.Daily -> sequence {
            var k = 0
            while (true) {
                yield(startDate.plusDays(k.toLong() * rule.every))
                k++
            }
        }

        is RepeatRule.Weekly -> sequence {
            val sortedDays = rule.days.sortedBy { it.value }
            val startWeekMonday = startDate.minusDays((startDate.dayOfWeek.value - 1).toLong())
            var w = 0
            while (true) {
                val weekMonday = startWeekMonday.plusDays(w.toLong() * rule.every * 7)
                for (day in sortedDays) {
                    val candidate = weekMonday.plusDays((day.value - 1).toLong())
                    if (!candidate.isBefore(startDate)) yield(candidate)
                }
                w++
            }
        }

        is RepeatRule.MonthlyOnDate -> sequence {
            var k = 0
            while (true) {
                val targetMonth = YearMonth.from(startDate).plusMonths(k.toLong() * rule.every)
                val clampedDay = minOf(rule.day, targetMonth.lengthOfMonth())
                val candidate = targetMonth.atDay(clampedDay)
                if (!candidate.isBefore(startDate)) yield(candidate)
                k++
            }
        }

        is RepeatRule.Yearly -> sequence {
            var k = 0
            while (true) {
                val year = startDate.year + k
                val ym = YearMonth.of(year, rule.month)
                val clampedDay = minOf(rule.day, ym.lengthOfMonth())
                val candidate = ym.atDay(clampedDay)
                if (!candidate.isBefore(startDate)) yield(candidate)
                k++
            }
        }
    }

    private fun epochToDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * Midnight on [date] in [zone]. `atStartOfDay(ZoneId)` - not
     * `atStartOfDay().atZone(...)` - because on a spring-forward DST date local
     * midnight may not exist, and the `ZoneId` overload resolves that to the
     * first valid instant instead of throwing or silently landing an hour out.
     */
    private fun dateToEpoch(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()
}
