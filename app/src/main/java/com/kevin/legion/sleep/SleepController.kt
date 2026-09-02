package com.kevin.legion.sleep

import android.content.Context
import com.kevin.legion.backend.BodyWriteThrough
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.meals.dayEndEpoch
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.util.shortDate
import java.util.UUID

/**
 * Orchestrates the sleep aspect (Kevin, 2026-08-07: "i want to be able to log sleep too") - mirrors
 * [com.kevin.legion.meals.MealController]'s shape field for field, since sleep is the same
 * "REPORTED tier, no reconciliation gate, copy-forward target" domain shape.
 */
object SleepController {
    /**
     * Logs one night's sleep. [durationHours] is parsed via [parseSleepDurationMinutes] - a null
     * result (an implausible or unparseable duration) is refused with a spoken reason rather than
     * silently written, the same "reject rather than guess" discipline
     * [com.kevin.legion.ledger.pendingAmountCents] uses for a spoken dollar amount.
     * [sleepDateOverride] lets a driver correct which night this was for ("log last night's sleep
     * as 8 hours" after waking up mid-afternoon); null defaults to `now`'s own wake-date, per
     * [SleepLog.sleepDate]'s doc comment.
     */
    suspend fun logSleep(
        context: Context,
        durationHours: Double,
        quality: Int?,
        notes: String?,
        sleepDateOverride: Long?,
        now: Long = System.currentTimeMillis(),
    ): String {
        val minutes = parseSleepDurationMinutes(durationHours)
            ?: return "That doesn't sound like a real duration - give me a number of hours between 0 and 24."
        val sleepDate = sleepDateOverride ?: dayStartEpoch(now)
        // BodyWriteThrough.addSleepLog, not a direct DAO insert - see MealController.logMeal's
        // own comment for the write-through shape this follows (body-supabase ticket).
        BodyWriteThrough.addSleepLog(
            context,
            SleepLog(
                sleepDate = sleepDate,
                durationMinutes = minutes,
                quality = quality,
                notes = notes,
                loggedAt = now,
                trustTier = TrustTier.REPORTED,
                guid = UUID.randomUUID().toString(),
                updatedAtMs = now,
            ),
        )
        val hoursText = formatMinutesAsHours(minutes)
        val qualityText = quality?.let { ", quality $it/5" } ?: ""
        return "Sleep logged: $hoursText$qualityText."
    }

    /** Sets the driver's nightly sleep target, effective from tonight's wake-date onward (D2's "copy forward"). */
    suspend fun setTarget(context: Context, targetHours: Double, now: Long = System.currentTimeMillis()): String {
        val minutes = parseSleepDurationMinutes(targetHours)
            ?: return "That doesn't sound like a real target - give me a number of hours between 0 and 24."
        val dayStart = dayStartEpoch(now)
        val db = CarDatabase.getDatabase(context)
        val guid = db.sleepTargetDao().getByEffectiveDate(dayStart)?.guid ?: UUID.randomUUID().toString()
        BodyWriteThrough.setSleepTarget(
            context,
            SleepTarget(targetMinutes = minutes, effectiveFromDateEpoch = dayStart, updatedAt = now, guid = guid),
        )
        return "Sleep target set: ${formatMinutesAsHours(minutes)} a night."
    }

    /** Tonight's (i.e. today's wake-date's) gap - see [SleepLog.sleepDate]'s doc comment for the wake-date convention. */
    suspend fun gapFor(context: Context, now: Long = System.currentTimeMillis()): SleepGap {
        val dayStart = dayStartEpoch(now)
        val dayEnd = dayEndEpoch(now)
        val db = CarDatabase.getDatabase(context)
        val target = db.sleepTargetDao().currentTarget(dayStart)?.targetMinutes
        val thatNight = db.sleepLogDao().forWindow(dayStart, dayEnd)
        return buildSleepGap(target, thatNight)
    }

    suspend fun recentSleep(context: Context, limit: Int = 20): List<SleepLog> =
        CarDatabase.getDatabase(context).sleepLogDao().getRecent(limit)

    /**
     * SLEEP panel + drilldown (ticket 16): every night within [fromMs, nowMs), keyed by
     * [SleepLog.sleepDate] - the daily-minutes bucketing source
     * ([com.kevin.legion.ui.bucketSleepMinutesDaily]) and the drilldown's history list both read
     * from this one call.
     */
    suspend fun sleepInWindow(context: Context, fromMs: Long, nowMs: Long = System.currentTimeMillis()): List<SleepLog> =
        CarDatabase.getDatabase(context).sleepLogDao().forWindow(fromMs, nowMs)

    /** See [com.kevin.legion.workouts.WorkoutController]'s matching doc comment - the cross-domain pick for `undo_last_log` happens in LiveToolbox. */
    suspend fun mostRecentSleepLog(context: Context): SleepLog? =
        CarDatabase.getDatabase(context).sleepLogDao().mostRecent()

    suspend fun deleteSleepLog(context: Context, log: SleepLog): String {
        BodyWriteThrough.deleteSleepLog(context, log)
        return "Undone: ${formatMinutesAsHours(log.durationMinutes)} of sleep logged ${shortDate(log.loggedAt)}."
    }
}

/** "7h 30m" / "8h" - whole hours drop the minutes remainder entirely rather than reading "8h 0m". */
fun formatMinutesAsHours(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}
