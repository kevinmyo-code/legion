package com.kevin.legion.advisor

import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlanItem
import java.time.DayOfWeek

/**
 * One day's worth of BIO checklist lines (ticket 04, `goal-plans`; day-slotting added ticket 07).
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
 * Turns whatever is currently written for the accepted BIO plan into today's checklist lines -
 * PURE, no [android.content.Context], no Room access, so this is a plain JVM unit test (ticket
 * 04's own verification asks for exactly this). The impure half - reading the current
 * [MealTarget]/[SleepTarget]/[WorkoutPlanItem] rows out of Room and reconciling them onto the
 * actual checklist [com.kevin.legion.data.local.ListItem]s - is [GoalChecklistSync], deliberately
 * kept in a separate file so this shaping logic stays testable without a database.
 *
 * **There is no stored "plan" row anywhere** (ticket 02's deliberate choice, carried forward by
 * ticket 03's settled call 1: a revision regenerates the whole plan, it never diffs one). So "the
 * accepted plan", as far as this function is concerned, IS whatever [MealTargetDao]/
 * [SleepTargetDao]/[WorkoutPlanItemDao] currently read back as effective - there is no fourth
 * thing to ask.
 *
 * **Day slotting is DERIVED, not stored (ticket 07).** [WorkoutPlanItem] (`data/local/
 * WorkoutPlanItem.kt`) still carries only "this exercise, this many sets THIS WEEK" - no
 * day-of-week column was added, on purpose: which day an exercise falls on is a pure function of
 * its position in the sorted exercise list and how many exercises the plan has, computed fresh on
 * every call by [dayForIndex], so a schema column would have stored a fact this function can always
 * re-derive from what already exists. Ticket 06's own assumptions ledger flagged the gap this
 * closes: a workout checklist line used to be "a standing weekly reminder rather than a specific
 * day's workout", shown all seven days regardless. [today] narrows [workoutItems] to only the
 * exercises [dayForIndex] assigns to it - every other day of the week that exercise is simply
 * absent from [items], never printed as a "rest" line (Kevin, `.scratch/goal-plans/issues/
 * 07-a-checklist-you-can-tick.md`: "a rest day shows no workout line at all").
 *
 * **Deterministic spreading, not model-assigned days.** [dayForIndex] spreads N sessions evenly
 * across Monday-Sunday with plain integer arithmetic - three sessions land on Monday/Wednesday/
 * Friday, one lands on Monday, seven fill every day. This was the explicit call in the ticket over
 * asking the recommender to name weekdays: the map's own accuracy bar is "loosely follow", and a
 * deterministic spread cannot hallucinate an eighth day or repeat one.
 *
 * **A mid-week plan change reassigns days for free.** Because the day a session falls on is
 * recomputed from the CURRENT [workoutItems] list on every call rather than read back from
 * something written at acceptance time, a revised plan's different exercise count or ordering
 * immediately produces a different spread the next time this runs - no migration of old
 * assignments required. What it does NOT touch is already-ticked history: [GoalChecklistSync]
 * only ever writes TODAY's row, so a `ListItem` materialized on an earlier day under an earlier
 * plan's assignment is never revisited by a later call here, and a session ticked on Tuesday under
 * last week's plan stays ticked even though this week's plan may no longer put anything on
 * Tuesday at all - a completed session is a fact about the past (ticket 07's own words), and this
 * function has no way to reach backward and un-happen it even if it wanted to; it only ever
 * describes today.
 */
object GoalChecklist {
    fun forToday(
        mealTarget: MealTarget?,
        sleepTarget: SleepTarget?,
        workoutItems: List<WorkoutPlanItem>,
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
        // Sorted by exercise name so the derived list is stable across calls that hand in the
        // same rows in a different order (Room gives no ordering guarantee on this query) - a
        // stable order matters here for two reasons now: GoalChecklistSync diffs this list's TEXT
        // against what is already on the checklist, not a diff against a previous derivation, AND
        // (ticket 07) the sorted position is the very input dayForIndex spreads across the week -
        // an unstable order would mean an exercise's assigned day could drift between calls with
        // no data actually changing.
        val sortedWorkouts = workoutItems.sortedBy { it.exercise.lowercase() }
        val sessionCount = sortedWorkouts.size
        sortedWorkouts.forEachIndexed { index, item ->
            if (dayForIndex(index, sessionCount) != today) return@forEachIndexed
            items += "${item.exercise}: ${item.targetSetsPerWeek} sets this week"
        }

        val hasPlan = mealTarget != null || sleepTarget != null || workoutItems.isNotEmpty()
        return GoalChecklistDay(hasPlan = hasPlan, items = items)
    }

    /**
     * Spreads [total] sessions evenly across the seven days of the week (Monday index 0) and
     * returns the day [index] (0-based, into the already-sorted exercise list) lands on. Plain
     * integer division, `floor(index * 7 / total)`: for `total = 3` this gives 0, 2, 4 -
     * Monday/Wednesday/Friday, exactly the spread the ticket names by example. Never called with
     * `total = 0` - every call site only reaches this once it knows `sessionCount > 0`.
     */
    private fun dayForIndex(index: Int, total: Int): DayOfWeek {
        val dayIndex = (index * 7) / total
        return DayOfWeek.of(dayIndex + 1)
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
