package com.kevin.legion.advisor

import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlanItem
import java.time.DayOfWeek

/**
 * One day's worth of BIO checklist lines (ticket 04, `goal-plans`; day-slotting added ticket 07;
 * daily-share prescription added ticket 08).
 *
 * [hasPlan] exists because an empty [items] list is ambiguous on its own - it is what "no plan has
 * ever been accepted", "a plan exists but happens to call for nothing today", AND (since ticket 07)
 * "today is a rest day and the only plan on file is a workout plan" all look like, and the ticket's
 * own rule ("an empty or unaccepted plan reads as 'no plan yet', never as zero progress") needs a
 * caller to be able to tell the first apart from the other two without re-deriving the same
 * nullability checks itself. `true` the moment ANY of meal/sleep/workout targets exist - it says
 * nothing about how many lines that produced, and **as of ticket 07 no longer implies at least one
 * item**: a workout-only plan on a day nothing is assigned to is a real, accepted plan with a
 * genuinely empty line list for today.
 */
data class GoalChecklistDay(
    val hasPlan: Boolean,
    val items: List<String>,
)

/**
 * One workout line, structured rather than just [text] - ticket 08's end-of-day auto-log needs to
 * turn a TICKED checklist line back into the `(exercise, sets, reps)` [WorkoutController.logSet]
 * takes, and the ticket's own instruction is to derive both the rendered text and the log
 * arguments from the SAME source rather than parsing the display string back apart. [GoalChecklistSync]'s
 * sweep matches a stored [com.kevin.legion.data.local.ListItem.text] against [text] here (both
 * built by [GoalChecklist.workoutLinesForDay] from the exact same plan row) and, on a match, reads
 * [exercise]/[sets]/[reps] straight off this data class - no string parsing anywhere in that path.
 */
data class WorkoutChecklistLine(val exercise: String, val sets: Int, val reps: Int?, val text: String)

/**
 * Turns whatever is currently written for the accepted BIO plan into today's checklist lines -
 * PURE, no [android.content.Context], no Room access, so this is a plain JVM unit test (ticket
 * 04's own verification asks for exactly this). The impure half - reading the current
 * [MealTarget]/[SleepTarget]/[WorkoutPlanItem] rows out of Room and reconciling them onto the
 * actual checklist [com.kevin.legion.data.local.ListItem]s - is [GoalChecklistSync], deliberately
 * kept in a separate file so this shaping logic stays testable without a database.
 *
 * **There is no stored "plan" row anywhere for meals/sleep** (ticket 02's deliberate choice,
 * carried forward by ticket 03's settled call 1: a revision regenerates the whole plan, it never
 * diffs one). The workout half DOES have a whole-plan row - [com.kevin.legion.data.local.WorkoutPlan],
 * read separately from its per-exercise [WorkoutPlanItem] rows - and [sessionsPerWeek] below is
 * that row's own field, threaded in by the caller.
 *
 * **Ticket 08 replaced ticket 07's "one exercise, one day" spread with a genuine daily
 * prescription.** Kevin's own ruling: *"kettlebell swing > 12 sets this week. it should be daily.
 * 3 sets x 10 rep kettlebell swing etc."* Ticket 07's model assigned each exercise to exactly ONE
 * day of the week and printed the whole weekly total there - "12 sets this week" showing through
 * on a single day is precisely the bug this rewrite fixes. **Every exercise in the plan now runs on
 * every one of the plan's [sessionsPerWeek] training days** (there is no per-exercise day-count
 * anywhere in the schema, only the whole-plan [WorkoutPlanItem]/[com.kevin.legion.data.local.WorkoutPlan]
 * split D21 already settled on - see that entity's own `sessionsPerWeek` doc comment for why it
 * exists at all), and each of those days shows that exercise's own SHARE of its weekly target,
 * computed by [weeklySplit] - "12 sets/week over 4 assigned days is 3 sets on each" (the ticket's
 * own worked example).
 *
 * **Deterministic spreading, not model-assigned days.** [assignedDays] spreads N session-days
 * evenly across Monday-Sunday with plain integer arithmetic, the same `floor(index * 7 / total)`
 * shape ticket 07's [assignedDays] (formerly `dayForIndex`) already used, just applied to N
 * SESSIONS instead of N EXERCISES - four sessions land on Monday/Tuesday/Thursday/Saturday, one
 * lands on Monday, seven fill every day ("it should be daily" read as literally as it can be:
 * seven sessions puts something on every day, exactly what Kevin asked for).
 *
 * **Uneven weekly totals split with the remainder to the EARLIEST assigned days ("earlier days
 * heavier"), never fractional, never silently dropped.** [weeklySplit] is the pure integer-division
 * function that proves this: for any `(total, sessions)` the returned per-day list always sums back
 * to exactly `total` - CLAUDE.md §4 rule 6's "a check that passes when nothing parsed is not a
 * gate" posture applied to arithmetic instead of ingestion: a split that silently dropped a
 * remainder set would be the exact same sin as a parser silently dropping an unrecognised row.
 *
 * **A mid-week plan change reassigns days for free**, same reasoning ticket 07 already established
 * for [assignedDays]: because both which days a session falls on and how a weekly total splits are
 * recomputed from the CURRENT plan on every call rather than read back from something written at
 * acceptance time, a revised plan immediately produces a different spread the next time this runs.
 * What it does NOT touch is already-ticked history - see [GoalChecklistSync]'s own doc comment.
 */
object GoalChecklist {
    fun forToday(
        mealTarget: MealTarget?,
        sleepTarget: SleepTarget?,
        workoutItems: List<WorkoutPlanItem>,
        /** [com.kevin.legion.data.local.WorkoutPlan.sessionsPerWeek] - how many distinct days a
         * week the whole plan trains, independent of which exercises happen on which day (D21's
         * own field). `null` when no [com.kevin.legion.data.local.WorkoutPlan] row exists at all
         * (a caller reading [WorkoutPlanItem]s with no matching whole-plan row, which should not
         * normally happen since [com.kevin.legion.workouts.WorkoutController.generatePlan] writes
         * both together in one call - defensive, not expected) - treated as "train every day" (7),
         * the same reading Kevin's own "it should be daily" gets when nothing more specific is on
         * file, rather than silently showing nothing. Clamped to `1..7` either way; a plan claiming
         * more than seven sessions a week cannot be honoured by a seven-day calendar. */
        sessionsPerWeek: Int? = null,
        /** Which day today is, Monday-Sunday - the caller's own local calendar day (see
         * [GoalChecklistSync.materializeToday]'s zone handling). Defaults to Monday only so this
         * function has a value at all for the many tests/callers that do not care about workout
         * day-slotting - a real caller always passes the actual day. */
        today: DayOfWeek = DayOfWeek.MONDAY,
    ): GoalChecklistDay {
        val items = mutableListOf<String>()

        mealTarget?.let {
            items += "Hit ${it.caloriesKcal} kcal / ${formatGrams(it.proteinG)}g protein"
        }
        sleepTarget?.let {
            items += "Sleep ${formatHours(it.targetMinutes)}"
        }

        items += workoutLinesForDay(workoutItems, sessionsPerWeek, today).map { it.text }

        val hasPlan = mealTarget != null || sleepTarget != null || workoutItems.isNotEmpty()
        return GoalChecklistDay(hasPlan = hasPlan, items = items)
    }

    /**
     * The structured half of [forToday]'s workout rendering, split out so [GoalChecklistSync]'s
     * end-of-day sweep can regenerate a PAST day's exact workout lines (as they were derived under
     * whatever plan was effective THAT week) and match a ticked [com.kevin.legion.data.local.ListItem]
     * back to its `(exercise, sets, reps)` by exact [WorkoutChecklistLine.text] equality - see that
     * data class's own doc for why this is the "derive both from the same source" answer to the
     * ticket's matching-robustness instruction, never a parse of the rendered string.
     */
    fun workoutLinesForDay(
        workoutItems: List<WorkoutPlanItem>,
        sessionsPerWeek: Int?,
        day: DayOfWeek,
    ): List<WorkoutChecklistLine> {
        if (workoutItems.isEmpty()) return emptyList()
        val sessions = (sessionsPerWeek ?: 7).coerceIn(1, 7)
        val dayIndex = assignedDays(sessions).indexOf(day)
        if (dayIndex == -1) return emptyList()

        // Sorted by exercise name so the derived list is stable across calls that hand in the
        // same rows in a different order (Room gives no ordering guarantee on this query) -
        // GoalChecklistSync diffs this list's TEXT against what is already on the checklist, not a
        // diff against a previous derivation, so a stable order matters.
        return workoutItems.sortedBy { it.exercise.lowercase() }.map { item ->
            val setsToday = weeklySplit(item.targetSetsPerWeek, sessions)[dayIndex]
            val repsPhrase = item.repsPerSet?.let { " x $it reps" } ?: ""
            WorkoutChecklistLine(
                exercise = item.exercise,
                sets = setsToday,
                reps = item.repsPerSet,
                text = "$setsToday sets$repsPhrase - ${item.exercise}",
            )
        }
    }

    /**
     * The [sessions] training days spread evenly across Monday(index 0)-Sunday(index 6), in
     * calendar order - `floor(i * 7 / sessions)` for `i in 0 until sessions`, ticket 07's own
     * spread shape (there named `dayForIndex`) applied to a SESSION COUNT rather than an exercise
     * index. For `sessions = 4` this gives 0, 1, 3, 5 - Monday/Tuesday/Thursday/Saturday. Index
     * INTO THIS LIST (not into the week) is what [weeklySplit]'s remainder favours as "earlier" -
     * see that function's own doc. Never called with `sessions = 0`; [forToday] always clamps to
     * `1..7` first.
     */
    private fun assignedDays(sessions: Int): List<DayOfWeek> =
        (0 until sessions).map { i -> DayOfWeek.of(((i * 7) / sessions) + 1) }

    /**
     * Splits [total] as evenly as possible across [sessions] days, remainder assigned to the
     * EARLIEST days first ("earlier days heavier" - the ticket's own words), and returns the list
     * in the same day-order [assignedDays] produced. **The property this exists to guarantee:
     * `weeklySplit(total, sessions).sum() == total` for every `total >= 0` and `sessions >= 1`** -
     * unit-tested directly as its own property (`GoalChecklistTest`), not just observed through
     * [forToday]'s rendered strings, the same "test the pure arithmetic, not just its formatting"
     * split [dayForIndex]'s ticket 07 predecessor already established. Plain integer division:
     * `base = total / sessions`, `remainder = total % sessions`; the first `remainder` entries get
     * `base + 1`, the rest get `base` - never fractional, never silently dropped (CLAUDE.md §4 rule
     * 6's "a check that passes when nothing parsed is not a gate" read as arithmetic).
     */
    internal fun weeklySplit(total: Int, sessions: Int): List<Int> {
        val base = total / sessions
        val remainder = total % sessions
        return (0 until sessions).map { i -> if (i < remainder) base + 1 else base }
    }

    /** Whole grams print without a decimal; anything else keeps one - matches the rest of this
     * screen's numeric convention (see `ui/BodyScreen.kt`'s own `formatWeight`). */
    private fun formatGrams(grams: Double): String =
        if (grams == grams.toLong().toDouble()) grams.toLong().toString() else "%.1f".format(grams)

    /** [minutes] as a spoken/read hour figure - "8h" or "7.5h", never raw minutes, matching
     * `set_sleep_target`'s own hours-first vocabulary. */
    private fun formatHours(minutes: Int): String {
        val hours = minutes / 60.0
        return if (hours == hours.toLong().toDouble()) "${hours.toLong()}h" else "%.1fh".format(hours)
    }
}
