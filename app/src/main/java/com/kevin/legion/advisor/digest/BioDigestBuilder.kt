package com.kevin.legion.advisor.digest

import android.content.Context
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.DigestBuilder
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.meals.dayEndEpoch
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier
import com.kevin.legion.sleep.SleepGap
import com.kevin.legion.sleep.buildSleepGap
import com.kevin.legion.sleep.formatMinutesAsHours
import com.kevin.legion.util.compactDate
import com.kevin.legion.workouts.buildWeeklyWorkoutGap
import com.kevin.legion.workouts.weekEndEpoch
import com.kevin.legion.workouts.weekStartEpoch
import java.time.ZoneId

/**
 * The BIO advisor's digest (ticket 16, off ticket 08's answer). Read-only over
 * [com.kevin.legion.data.local.BodyweightLogDao]/[com.kevin.legion.data.local.MealLogDao]/
 * [com.kevin.legion.data.local.MealTargetDao]/[com.kevin.legion.data.local.WorkoutPlanDao]/
 * [com.kevin.legion.data.local.WorkoutSetLogDao]/[com.kevin.legion.data.local.SleepLogDao]/
 * [com.kevin.legion.data.local.SleepTargetDao]/[com.kevin.legion.data.local.GoalDao] - never writes,
 * never blocks on network, per [DigestBuilder]'s contract.
 *
 * **Window (ticket 08 answer call 3): current period + 3 prior, weeks for BIO.** Applied to the two
 * domains that are genuinely weekly (bodyweight, session count) as a real 4-week series with a trend
 * figure; applied to the two domains that are genuinely daily (intake, sleep) as the CURRENT week's
 * days, since a week IS the period boundary here and a driver asking "am I on track" cares about
 * this week's daily pattern, not a flattened 28-day list that would blow the token budget for no
 * coaching value. This split is a **reasoned** reading of ticket 16's literal "per day" (intake) vs
 * un-qualified (sleep, sessions) wording against ticket 08's window law, not a fact read verbatim out
 * of either ticket - flagged in the build report.
 *
 * **Never routes a figure around [DigestText]** - every emitted number is either wrapped through
 * [DigestText.line]/[DigestText.withTier]/[DigestText.estimate], or is one of this file's own two
 * private label constants (`"not logged"`'s day-of-week labels), which are pure formatting, not a
 * competing vocabulary for tier/unverified/estimate.
 */
class BioDigestBuilder : DigestBuilder {
    override val aspect = AdvisorAspect.BIO

    override suspend fun build(context: Context): String {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val lines = mutableListOf<String>()
        lines += weightLine(db, now, zone)
        lines += intakeLine(db, now, zone)
        lines += sessionsLine(db, now, zone)
        stalledLiftLine(db, zone)?.let { lines += it }
        lines += sleepLine(db, now, zone)
        goalLines(db)?.let { lines += it }

        return lines.joinToString("\n")
    }

    /**
     * D23/ticket 08: "bodyweight WEEKLY AVERAGES (never daily, per the playbook) + trend." Four
     * weeks (current + 3 prior), each averaged from same-unit readings only - a driver who has ever
     * switched lbs/kg mid-history would otherwise get a nonsense average of two different scales, so
     * this builder reports against whichever unit [BodyweightLogDao.mostRecent] used and silently
     * excludes older readings logged in the other unit from the AVERAGE (they still count toward
     * [TrustTier] via every row actually included). **Reasoned simplification, not unit conversion**
     * - flagged in the build report.
     */
    private suspend fun weightLine(db: CarDatabase, now: Long, zone: ZoneId): String {
        val dao = db.bodyweightLogDao()
        val latestUnit = dao.mostRecent()?.weightUnit ?: return DigestText.line("WEIGHT", DigestText.notLogged())
        val weekMs = 7L * 24 * 60 * 60 * 1000

        val avgsByWeek = mutableMapOf<Int, Double>()
        val tiers = mutableListOf<TrustTier>()
        val parts = mutableListOf<String>()
        for (wk in 0..3) {
            val shifted = now - wk * weekMs
            val start = weekStartEpoch(shifted, zone)
            val end = weekEndEpoch(shifted, zone)
            val readings = dao.forWindow(start, end).filter { it.weightUnit == latestUnit }
            val label = if (wk == 0) "wk0" else "wk-$wk"
            if (readings.isEmpty()) {
                parts += "$label ${DigestText.notLogged()}"
            } else {
                val avg = readings.sumOf { it.weightValue } / readings.size
                avgsByWeek[wk] = avg
                tiers += readings.map { it.trustTier }
                parts += "$label ${formatOneDecimal(avg)}$latestUnit"
            }
        }
        if (avgsByWeek.isEmpty()) return DigestText.line("WEIGHT", DigestText.notLogged())

        val oldestWk = avgsByWeek.keys.max()
        if (avgsByWeek.containsKey(0) && oldestWk != 0) {
            val delta = avgsByWeek.getValue(0) - avgsByWeek.getValue(oldestWk)
            val sign = if (delta >= 0) "+" else ""
            parts += "trend $sign${formatOneDecimal(delta)}$latestUnit/${oldestWk}wk"
        }
        return DigestText.withTier(DigestText.line("WEIGHT", parts.joinToString(" ")), tiers.combinedTier())
    }

    /**
     * D26/D27: intake vs [com.kevin.legion.data.local.MealTarget] for every day of the CURRENT week
     * so far, unlogged days named per D27's own rule (never coerced to 0 kcal). Calorie figures are
     * always LLM estimates ([com.kevin.legion.data.local.MealLog]'s own doc comment: "a plate of
     * food... never states its own calorie count"), so the whole line is wrapped through
     * [DigestText.estimate] (CLAUDE.md §4 rule 5) - never just the tier tag, which speaks to
     * provenance of the LOG entry, not to whether the number itself was guessed.
     */
    private suspend fun intakeLine(db: CarDatabase, now: Long, zone: ZoneId): String {
        val target = db.mealTargetDao().currentTarget(dayStartEpoch(now, zone))
            ?: return DigestText.line("INTAKE", DigestText.notLogged())

        val dayLabels = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val weekStart = weekStartEpoch(now, zone)
        val today = dayStartEpoch(now, zone)
        val tiers = mutableListOf<TrustTier>()
        val days = mutableListOf<String>()
        var cursor = weekStart
        var idx = 0
        while (cursor <= today) {
            val dayEnd = dayEndEpoch(cursor, zone)
            val meals = db.mealLogDao().forWindow(cursor, dayEnd)
            val label = dayLabels.getOrElse(idx) { "d$idx" }
            if (meals.isEmpty()) {
                days += "$label ${DigestText.notLogged()}"
            } else {
                val kcal = meals.sumOf { it.caloriesKcal ?: 0 }
                days += "$label $kcal"
                tiers += meals.map { it.trustTier }
            }
            cursor = dayEnd
            idx++
        }
        val value = DigestText.estimate("target ${target.caloriesKcal}kcal " + days.joinToString(" "))
        return DigestText.withTier(DigestText.line("INTAKE", value), tiers.combinedTier())
    }

    /**
     * D24: "sessions done versus sessions planned, this week" - reused verbatim via
     * [buildWeeklyWorkoutGap], run over the current + 3 prior weeks (ticket 08's window). A week
     * with no [com.kevin.legion.data.local.WorkoutPlan] effective yet is named `not logged` per that
     * same week's slot, never coerced into a 0-target gap.
     */
    private suspend fun sessionsLine(db: CarDatabase, now: Long, zone: ZoneId): String {
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val tiers = mutableListOf<TrustTier>()
        val parts = mutableListOf<String>()
        var anyPlan = false
        for (wk in 0..3) {
            val shifted = now - wk * weekMs
            val start = weekStartEpoch(shifted, zone)
            val end = weekEndEpoch(shifted, zone)
            val label = if (wk == 0) "wk0" else "wk-$wk"
            val plan = db.workoutPlanDao().currentPlan(start)
            if (plan == null) {
                parts += "$label ${DigestText.notLogged()}"
                continue
            }
            anyPlan = true
            val sets = db.workoutSetLogDao().forWindow(start, end)
            val gap = buildWeeklyWorkoutGap(plan.sessionsPerWeek, sets, zone)
            tiers += gap.tier
            parts += "$label ${gap.actual}/${gap.target}"
        }
        if (!anyPlan) return DigestText.line("SESSIONS", DigestText.notLogged())
        return DigestText.withTier(DigestText.line("SESSIONS", parts.joinToString(" ")), tiers.combinedTier())
    }

    /**
     * Ticket 16: "per-exercise progression naming the stalled lift" - one named exemplar (ticket
     * 08's "aggregates plus a FEW named exemplars"), never a full per-exercise dump. A "stall" here
     * is a trailing run of session-days that failed to beat the best max weight logged before them
     * for that exercise - the same "session = a calendar day" unit
     * [com.kevin.legion.workouts.buildWeeklyWorkoutGap] uses, extended across all history rather than
     * one week. Returns null (line omitted, not "not logged" - there is no single stalled-lift
     * DOMAIN to report empty, only an optional exemplar) when no exercise has a 2+ session stall.
     */
    private suspend fun stalledLiftLine(db: CarDatabase, zone: ZoneId): String? {
        val exercises = db.workoutSetLogDao().distinctExercisesByRecency().take(5)
        var bestExercise: String? = null
        var bestStreak = 1
        for (candidate in exercises) {
            val sets = db.workoutSetLogDao().forExercise(candidate.exercise)
            val weighted = sets.filter { it.weightValue != null }
            if (weighted.size < 2) continue
            val dayMaxes = weighted
                .groupBy { dayStartEpoch(it.loggedAt, zone) }
                .toSortedMap()
                .values
                .map { day -> day.maxOf { it.weightValue!! } }
            if (dayMaxes.size < 2) continue
            var bestSoFar = dayMaxes[0]
            var streak = 0
            for (i in 1 until dayMaxes.size) {
                if (dayMaxes[i] > bestSoFar) {
                    bestSoFar = dayMaxes[i]
                    streak = 0
                } else {
                    streak++
                }
            }
            if (streak >= 2 && streak > bestStreak) {
                bestStreak = streak
                bestExercise = candidate.exercise
            }
        }
        val exercise = bestExercise ?: return null
        return DigestText.withTier(
            DigestText.line("LIFT", "$exercise stalled $bestStreak sessions"),
            TrustTier.REPORTED, // every WorkoutSetLog row is REPORTED by construction - see its doc comment.
        )
    }

    /**
     * Tonight's (today's wake-date's) sleep vs [com.kevin.legion.data.local.SleepTarget] - reuses
     * [buildSleepGap] verbatim, matching [com.kevin.legion.sleep.SleepController.gapFor]'s own
     * window exactly rather than re-deriving it. Ticket 16 does not ask for a per-day sleep window
     * (unlike intake's explicit "per day") - see this file's class doc comment for the reasoning
     * that split the two.
     */
    private suspend fun sleepLine(db: CarDatabase, now: Long, zone: ZoneId): String {
        val dayStart = dayStartEpoch(now, zone)
        val dayEnd = dayEndEpoch(now, zone)
        val target = db.sleepTargetDao().currentTarget(dayStart)?.targetMinutes
        val thatNight = db.sleepLogDao().forWindow(dayStart, dayEnd)
        val gap = buildSleepGap(target, thatNight)
        return when (gap) {
            is SleepGap.NotLogged -> DigestText.line("SLEEP", DigestText.notLogged())
            is SleepGap.Logged -> DigestText.withTier(
                DigestText.line(
                    "SLEEP",
                    "target ${formatMinutesAsHours(gap.gap.target)} actual ${formatMinutesAsHours(gap.gap.actual)}",
                ),
                gap.gap.tier,
            )
        }
    }

    /**
     * Ticket 08: "every digest also carries the aspect's goals." [com.kevin.legion.data.local.Goal]
     * carries no [TrustTier] (it is an intention, not a claim about the world - see its own doc
     * comment), so a goal line is never tier-tagged. Returns null (omitted, not "not logged") when
     * there are no active BIO goals - an absent goal is not a recording gap the way an unlogged meal
     * is.
     */
    private suspend fun goalLines(db: CarDatabase): String? {
        val goals = db.goalDao().currentGoals(AdvisorAspect.BIO.key)
        if (goals.isEmpty()) return null
        return goals.joinToString("\n") { g ->
            val extras = buildList {
                if (g.targetValue != null) add("target ${g.targetValue}${g.unit ?: ""}")
                if (g.deadlineEpoch != null) add("by ${compactDate(g.deadlineEpoch)}")
            }
            DigestText.line("GOAL", (listOf(g.statement) + extras).joinToString(" "))
        }
    }

    private fun formatOneDecimal(value: Double): String = "%.1f".format(value)
}
