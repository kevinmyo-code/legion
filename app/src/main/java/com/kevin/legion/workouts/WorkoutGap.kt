package com.kevin.legion.workouts

import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * D24: "The gap is sessions done versus sessions planned, this week. Not volume, not tonnage, not
 * per-exercise adherence." Pure - no Room, no Android - mirrors
 * [com.kevin.legion.ledger.buildBudgetVsActual]'s shape: a function of already-fetched rows and a
 * target, unit-testable without Robolectric.
 *
 * A "session" here is a distinct CALENDAR DAY (in [zone], the device's zone in production - see
 * [weekStartEpoch]) on which at least one [WorkoutSetLog] was
 * logged, counted once no matter how many exercises or sets happened that day - this is what D24
 * rules OUT "per-exercise adherence" for: a day with squats and bench both logged is still one
 * session, not two. [WorkoutPlan.sessionsPerWeek] is the target this counts against - see its own
 * doc comment for why that field exists at all.
 *
 * Every row here comes from voice logging, so [WorkoutSetLog.trustTier] is [TrustTier.REPORTED]
 * unconditionally - there is no PROVEN workout data path today. [PlanGap.tier] is computed via
 * [combinedTier] exactly like [com.kevin.legion.ledger.buildBudgetVsActual] does, rather than
 * reimplementing ticket 05 D6's "one reported actual taints the whole gap" rule here: an empty
 * week reduces to [TrustTier.PROVEN] (nothing to be cautious about yet, per [combinedTier]'s own
 * doc comment), any logged week is [TrustTier.REPORTED].
 */
fun buildWeeklyWorkoutGap(
    sessionsPlanned: Int,
    setsThisWeek: List<WorkoutSetLog>,
    zone: ZoneId = ZoneId.systemDefault(),
): PlanGap<Int> {
    val sessionsDone = setsThisWeek
        .map { Instant.ofEpochMilli(it.loggedAt).atZone(zone).toLocalDate() }
        .distinct()
        .size
    return PlanGap(
        target = sessionsPlanned,
        actual = sessionsDone,
        gap = sessionsPlanned - sessionsDone,
        tier = setsThisWeek.map { it.trustTier }.combinedTier(),
    )
}

/**
 * Monday-start of the week containing [epochMs], **in [zone]** (the device's zone in production) -
 * the period boundary [WorkoutPlan]/[WorkoutPlanItem] key off
 * ([effectiveFromWeekEpoch][com.kevin.legion.data.local.WorkoutPlan.effectiveFromWeekEpoch]) and
 * [buildWeeklyWorkoutGap]'s caller windows [WorkoutSetLog] rows by.
 *
 * **This used to be pinned to `ZoneOffset.UTC`, and that was a bug (Kevin, 2026-08-07).** It is
 * the same UTC-versus-device-zone mismatch [com.kevin.legion.notes.Recurrence.DEFAULT_ZONE]
 * documents, in a second domain: [WorkoutSetLog.loggedAt], [com.kevin.legion.data.local.MealLog.loggedAt]
 * and [com.kevin.legion.data.local.SleepLog.loggedAt] are all `System.currentTimeMillis()` REAL
 * INSTANTS, rendered in the device's zone by [com.kevin.legion.util.shortDate], while every
 * "today" and "this week" window was cut on UTC boundaries. West of UTC the two disagree for the
 * whole evening: at America/Chicago (UTC-5), UTC's Aug 8 begins at 19:00 local on Aug 7, so a set
 * logged at 21:00 on Aug 7 rendered its own date as "Aug 7" (correct) while today's gap - reading
 * a window that had already rolled to Aug 8 - left it out. The row was right, the aggregate was
 * wrong, and nothing on screen said so. East of UTC it shifts the other way, swallowing the local
 * small hours into the previous day.
 *
 * [com.kevin.legion.util.documentDate]'s UTC convention is NOT the same thing and is unchanged: a
 * statement's printed transaction date is date-only by construction with no instant behind it, so
 * it must be read back in the zone it was written in. A timestamp captured from a clock is the
 * opposite case - it belongs to the day the driver was living when it was captured.
 *
 * Tests pass an explicit zone so they stay deterministic on any machine; production takes the
 * default.
 */
fun weekStartEpoch(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * The EXCLUSIVE end of [weekStartEpoch]'s week - next Monday's local midnight. Advances the
 * calendar rather than adding 7 * 24h, for the DST reason [com.kevin.legion.meals.dayEndEpoch]
 * spells out.
 */
fun weekEndEpoch(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
