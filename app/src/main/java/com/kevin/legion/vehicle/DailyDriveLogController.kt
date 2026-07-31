package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.CompanionIdentity
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.billing.EntitlementManager
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DailyDriveLog
import java.util.Calendar

/**
 * Daily drive logs (E7) - the lightest of the three Wrapped-family tiers
 * (daily / [MonthlyRecapController] / [YearlyWrappedController]). No cover
 * art: unlike the monthly and yearly tiers, a daily log stays "single
 * colored" per Kevin's call (2026-07-08) - a quick note, not an occasion,
 * and it skips the image-generation cost entirely. Otherwise mirrors
 * MonthlyRecapController's shape: aggregate the same TRIP_MILES/MPG_TRIP
 * samples and code_events, just scoped to one calendar day instead of a
 * month, with a shorter one-line narrative.
 */
object DailyDriveLogController {
    private const val TAG = "DailyDriveLogController"
    // How stale today's log may get before the next check regenerates it. The
    // service's recap loop runs hourly, so this is effectively "at most once an
    // hour"; the real cost control is generate()'s unchanged-numbers skip, which
    // means a day of not driving costs one Gemini call, not one per hour.
    private const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L
    private const val NOTABLE_LONG_DRIVE_MILES = 150.0
    private const val NOTABLE_CODE_COUNT = 2

    // Car profiles (2026-07-16): the driver's picked car, falling back to the
    // connected dongle. NOT the dongle MAC directly any more - one dongle moved
    // between two cars used to make them the same vehicle. See ActiveVehicle.
    private fun vehicleId(context: Context): String = ActiveVehicle.current(context)

    /**
     * Keeps TODAY's log current and finalises YESTERDAY's. Called on the service's
     * hourly recap loop; both halves are cheap no-ops when there's nothing to do.
     *
     * Changed 2026-07-16 (Kevin): this used to generate YESTERDAY's finished log,
     * once, only within the first 6 hours of a new day - so today's driving was
     * invisible until tomorrow morning. Now today's log exists from the first
     * check of the day and updates as the day goes on, and the day is cut at
     * 23:59:59.999 (see [dayRange]).
     */
    suspend fun refreshIfDue(context: Context) {
        runCatching { finalizeYesterday(context) }
            .onFailure { Log.w(TAG, "Daily drive log finalize failed: ${it.message}") }
        runCatching { refreshToday(context) }
            .onFailure { Log.w(TAG, "Daily drive log refresh failed: ${it.message}") }
    }

    /**
     * Regenerates today's log if it's stale, so the driver sees the day so far
     * rather than nothing until tomorrow.
     *
     * Throttled by [REFRESH_INTERVAL_MS], and [generate] additionally skips the
     * Gemini call when the day's numbers haven't moved - so a parked car costs one
     * call for the whole day, not one per check.
     */
    private suspend fun refreshToday(context: Context) {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val id = vehicleId(context)
        val existing = CarDatabase.getDatabase(context).dailyDriveLogDao().getForDay(id, year, month, day)
        if (existing != null && System.currentTimeMillis() - existing.generatedAt < REFRESH_INTERVAL_MS) return

        generate(context, id, year, month, day)
    }

    /**
     * Gives yesterday one last pass so a drive that happened after its final
     * in-day refresh still lands in it, then leaves it alone forever.
     *
     * "Already final" is derived, not stored: a log written DURING its own day
     * necessarily has `generatedAt` inside that day, and this pass runs after the
     * day has rolled, so its rewrite stamps a `generatedAt` past the day's end.
     * That comparison IS the finalised flag, which is why this needs no schema
     * change and no migration.
     *
     * Also covers the case the old grace-window code existed for: if the app
     * wasn't running yesterday at all, `existing` is null and this generates it
     * from scratch.
     */
    private suspend fun finalizeYesterday(context: Context) {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val id = vehicleId(context)
        val existing = CarDatabase.getDatabase(context).dailyDriveLogDao().getForDay(id, year, month, day)
        val (_, dayEndMs) = dayRange(year, month, day)
        if (existing != null && existing.generatedAt > dayEndMs) return // already cut

        generate(context, id, year, month, day, force = existing != null)
    }

    /**
     * Generates and saves the log for [vehicleId]/[year]/[month]/[day], replacing
     * any existing row for that day in place.
     *
     * [force] rewrites even when the numbers are unchanged - used by the finalise
     * pass, whose whole job is to stamp a post-day `generatedAt`. Otherwise an
     * unchanged day returns the existing row untouched WITHOUT calling Gemini:
     * this now runs repeatedly through the day on the driver's own key, so
     * regenerating identical prose every hour would be spending their money to
     * produce the same sentence.
     */
    suspend fun generate(
        context: Context,
        vehicleId: String,
        year: Int,
        month: Int,
        day: Int,
        force: Boolean = false,
    ): DailyDriveLog {
        val (fromMs, toMs) = dayRange(year, month, day)
        val db = CarDatabase.getDatabase(context)

        val tripMiles = db.odbSampleDao().getRange(vehicleId, "TRIP_MILES", fromMs, toMs)
        val mpgSamples = db.odbSampleDao().getRange(vehicleId, "MPG_TRIP", fromMs, toMs)
        val milesDriven = tripMiles.sumOf { it.value }
        val driveCount = tripMiles.size
        val longestDrive = tripMiles.maxOfOrNull { it.value }
        val avgMpg = mpgSamples.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.value } / s.size }
        val codeCount = db.codeEventDao().countInRange(vehicleId, fromMs, toMs)

        val existing = db.dailyDriveLogDao().getForDay(vehicleId, year, month, day)
        val unchanged = existing != null &&
            existing.driveCount == driveCount &&
            existing.codeEventCount == codeCount &&
            existing.milesDriven == milesDriven
        if (unchanged && !force) return existing!!

        val notableReason = when {
            (longestDrive ?: 0.0) >= NOTABLE_LONG_DRIVE_MILES -> "longest drive: ${longestDrive!!.toInt()} mi"
            codeCount >= NOTABLE_CODE_COUNT -> "$codeCount trouble codes today"
            else -> null
        }

        // Reuse the narrative on a forced-but-unchanged rewrite (the finalise
        // pass): the numbers are identical, so a fresh Gemini call would buy a
        // reworded version of the same sentence on the driver's key.
        val narrative = if (unchanged && existing != null) existing.narrative
        else generateNarrative(context, milesDriven, driveCount, avgMpg, codeCount)

        val log = DailyDriveLog(
            // Carry the existing row's PK so REPLACE updates in place. Without
            // this, id defaults to 0, autoGenerate mints a fresh one, and every
            // refresh would append a DUPLICATE row for the same day rather than
            // replacing it - REPLACE only fires on a PK/unique conflict, and there
            // is no unique index on (vehicleId, year, month, day).
            id = existing?.id ?: 0,
            vehicleId = vehicleId, year = year, month = month, day = day,
            generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, avgMpg = avgMpg, driveCount = driveCount,
            codeEventCount = codeCount, narrative = narrative,
            notable = notableReason != null, notableReason = notableReason,
        )
        db.dailyDriveLogDao().insert(log)
        return log
    }

    /** Debug-only: fabricates a plausible day in the past 2 weeks, real Gemini narrative call, no real aggregation. */
    suspend fun generateFake(context: Context, vehicleId: String): DailyDriveLog {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -(0..13).random()) }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val notable = listOf(true, false, false, false).random()
        val milesDriven = (5..80).random().toDouble()
        val driveCount = (1..5).random()
        val avgMpg = (16..30).random().toDouble()
        val codeCount = if (notable) (2..4).random() else 0

        val narrative = generateNarrative(context, milesDriven, driveCount, avgMpg, codeCount)
        val notableReason = if (codeCount >= NOTABLE_CODE_COUNT) "$codeCount trouble codes today" else null

        val log = DailyDriveLog(
            vehicleId = vehicleId, year = year, month = month, day = day,
            generatedAt = System.currentTimeMillis(),
            milesDriven = milesDriven, avgMpg = avgMpg, driveCount = driveCount,
            codeEventCount = codeCount, narrative = narrative,
            notable = notableReason != null, notableReason = notableReason,
        )
        CarDatabase.getDatabase(context).dailyDriveLogDao().insert(log)
        return log
    }

    // The numbers above (miles, drives, MPG, codes) are always computed and saved for
    // free - this narrative is the only Gemini-billed part of a daily log, so it's the
    // only part gated by AI mode. Ungated, it falls back to the same numbers-only line
    // used when the agent call itself fails - the driver always gets a note, just not
    // the AI-written one until they've unlocked or subscribed.
    private suspend fun generateNarrative(
        context: Context,
        milesDriven: Double,
        driveCount: Int,
        avgMpg: Double?,
        codeCount: Int,
    ): String {
        val fallback = "$driveCount drives, ${milesDriven.toInt()} miles today."
        if (!EntitlementManager.canUseSubAgent()) return fallback
        val stats = buildString {
            append("Miles driven today: ${milesDriven.toInt()}. ")
            append("Drives: $driveCount. ")
            avgMpg?.let { append("Average MPG: ${"%.1f".format(it)}. ") }
            append("Trouble codes: $codeCount.")
        }
        val system = CompanionIdentity.shortClause(context) + " " +
            "You are noting today's driving in one line for the driver. Given today's stats, write " +
            "exactly ONE short, warm, spoken-natural sentence - a quick daily note, not a full " +
            "recap. Plain text only, no markdown."
        val agent = SubAgent(systemInstruction = system, useSearch = false)
        return agent.ask(stats, "Write today's note.") ?: fallback
    }

    private fun dayRange(year: Int, month: Int, day: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, 0, 0, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val to = cal.timeInMillis - 1
        return from to to
    }
}
