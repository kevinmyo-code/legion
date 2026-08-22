package com.kevin.legion.advisor

import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlanItem

/**
 * One day's worth of BIO checklist lines (ticket 04, `goal-plans`).
 *
 * [hasPlan] exists because an empty [items] list is ambiguous on its own - it is what BOTH "no
 * plan has ever been accepted" and "a plan exists but happens to call for nothing today" look
 * like, and the ticket's own rule ("an empty or unaccepted plan reads as 'no plan yet', never as
 * zero progress") needs a caller to be able to tell those apart without re-deriving the same three
 * nullability checks itself. `true` the moment ANY of meal/sleep/workout targets exist - it says
 * nothing about how many lines that produced.
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
 * **No per-day slotting.** [WorkoutPlanItem] (`data/local/WorkoutPlanItem.kt`) carries only "this
 * exercise, this many sets THIS WEEK" - no day-of-week column exists anywhere in the workouts
 * schema (`.scratch/legion-shape/issues/08-workouts-domain.md` D20's own "no periodisation, no
 * progression model, no day slotting" call). A workout line here is therefore a standing weekly
 * reminder, shown every day until the week's target is logged elsewhere, matching Kevin's
 * "loosely follow" accuracy bar (`.scratch/goal-plans/map.md`) rather than inventing a day-by-day
 * schedule this domain has never stored.
 */
object GoalChecklist {
    fun forToday(
        mealTarget: MealTarget?,
        sleepTarget: SleepTarget?,
        workoutItems: List<WorkoutPlanItem>,
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
        // stable order matters here because GoalChecklistSync diffs this list's TEXT against
        // what is already on the checklist, not a diff against a previous derivation.
        workoutItems.sortedBy { it.exercise.lowercase() }.forEach {
            items += "${it.exercise}: ${it.targetSetsPerWeek} sets this week"
        }

        val hasPlan = mealTarget != null || sleepTarget != null || workoutItems.isNotEmpty()
        return GoalChecklistDay(hasPlan = hasPlan, items = items)
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
