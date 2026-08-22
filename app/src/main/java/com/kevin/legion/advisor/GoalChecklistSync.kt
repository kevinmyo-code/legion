package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatRule
import com.kevin.legion.workouts.weekStartEpoch

/**
 * Reconciles the accepted BIO plan onto **the** checklist (`notes/NotesController.theList`) -
 * ticket 04, `goal-plans`. Called once, from `service/LiveToolbox.kt`'s `accept_goal_plan`
 * dispatch, after every write `generate_goal_plan`'s own flow makes (`set_meal_target`/
 * `set_sleep_target`/`set_goal`, plus [GoalPlanAgent.accept]'s own workout-plan write) has
 * already landed - see [sync]'s own doc comment for why reading Room fresh at that point is
 * "the accepted plan" rather than something this object has to be handed explicitly.
 *
 * **Routed entirely through [NotesController]'s existing writes** - [NotesController.addItem],
 * [NotesController.setRepeat], [NotesController.removeItem] - never a new DAO method and never a
 * new table. This is the ticket's own rule ("do not build a second ticking path") read as broadly
 * as it should be: a second WRITE path for the same [com.kevin.legion.data.local.ListItem] table
 * would be exactly as risky as a second per-occurrence completion column, for the same reason -
 * two places deciding what a checklist line is are two places that can quietly disagree.
 */
object GoalChecklistSync {
    /**
     * The visible marker on every line this object writes. Deliberately visible text, not hidden
     * metadata like [GoalPlanAgent.CONSTRAINT_PREFIX] - a plan line reading "Plan: Hit 2,300 kcal
     * / 180g protein" tells the user where it came from at a glance. It doubles as
     * [sync]'s own way of telling a plan line apart from an item the user added by hand (a
     * recurring "take the bins out" reminder, say) sitting on the same single list, so a stale
     * line from a superseded target can be found and removed without sweeping up anything else.
     */
    const val ITEM_PREFIX = "Plan: "

    /**
     * Reads the current [GoalChecklist] lines straight from Room and reconciles them onto the
     * checklist:
     * - a derived line with no matching [ITEM_PREFIX] item yet is added, as a recurring DAILY
     *   item with no end date and no `startsAt` - it needs no clock trigger (this is a checklist,
     *   not an alarm), so [com.kevin.legion.notes.Recurrence] never has to run for it and
     *   `AlarmScheduler` never arms anything for it ([NotesController.setRepeat]'s own
     *   `scheduleAlarmFor` no-ops on a null `startsAt`, confirmed by reading it before writing
     *   this);
     * - an existing [ITEM_PREFIX] item whose text is no longer among the derived lines is removed
     *   (soft-deleted, [NotesController.removeItem]'s existing tombstone) - a target that changed
     *   or was refused on a later acceptance should not leave its old line on the checklist
     *   forever;
     * - an existing [ITEM_PREFIX] item whose text still matches is left completely alone, so its
     *   [com.kevin.legion.data.local.ListItemSkip] history reads as continuous across a
     *   re-acceptance that did not actually change that particular line.
     *
     * **A recurring [com.kevin.legion.data.local.ListItem] can never be ticked**
     * ([NotesController.tick] refuses one outright and returns `false` without writing anything -
     * `notes/NotesController.kt`'s own doc comment: "a recurring item can never be ticked...
     * removes per-occurrence completion state entirely"). The only per-occurrence voice
     * affordance a user has for one of these lines is `manage_item`'s `skip` action - "skip
     * today's occurrence" - never a tick. **This is not a gap introduced here**; it is
     * `notes-lists-calendar` ticket 04's own, already-shipped design, inherited unchanged because
     * this map's own rule ("do not build a second ticking path") forbids adding a second
     * per-occurrence state store to work around it. The build report's assumptions ledger says
     * this plainly rather than letting "tickable" in this ticket's own prose stand uncorrected.
     */
    suspend fun sync(context: Context, now: Long = System.currentTimeMillis()) {
        val db = CarDatabase.getDatabase(context)
        val mealTarget = db.mealTargetDao().currentTarget(dayStartEpoch(now))
        val sleepTarget = db.sleepTargetDao().currentTarget(dayStartEpoch(now))
        val workoutItems = db.workoutPlanItemDao().currentItems(weekStartEpoch(now))

        val derived = GoalChecklist.forToday(mealTarget, sleepTarget, workoutItems)
        val wantedTexts = derived.items.map { ITEM_PREFIX + it }.toSet()

        val list = NotesController.theList(context)
        val existing = NotesController.allRecurringItems(context)
            .filter { it.listId == list.id && it.text.startsWith(ITEM_PREFIX) }
        val existingTexts = existing.map { it.text }.toSet()

        existing.filter { it.text !in wantedTexts }.forEach { NotesController.removeItem(context, it) }

        wantedTexts.filter { it !in existingTexts }.forEach { text ->
            val item = NotesController.addItem(context, list.id, text)
            NotesController.setRepeat(context, item, RepeatRule.Daily(every = 1), RepeatEnd.Never)
        }
    }

    /**
     * One checklist line as read back for a screen - [text] with [ITEM_PREFIX] already stripped
     * (a screen shows "Hit 2,300 kcal...", not "Plan: Hit 2,300 kcal..."), plus the ONLY
     * per-occurrence fact this schema actually stores: whether THIS date was explicitly skipped.
     *
     * **There is no "done" here, and there never can be without a second per-occurrence store**
     * ([sync]'s own doc comment). [skippedToday] and [recentSkipDates] are a record of what the
     * user opted OUT of, never a record of what they did - a caller must not print "on track" or
     * a percentage from these fields (CLAUDE.md §7's compulsion ban, and ticket 04's own "adherence
     * is shown, never scored"). The honest sentence this data supports is "here is what was
     * skipped", not "here is what was completed".
     */
    data class GoalChecklistItemView(
        val text: String,
        val skippedToday: Boolean,
        /** Skip dates (device-zone UTC-midnight-of-day epoch ms, matching how `manage_item`'s
         * `skip` action itself parses a date - see `service/LiveToolbox.kt`'s `parseNoteDate`)
         * within the last [RECENT_SKIP_WINDOW_DAYS] days, most recent first. */
        val recentSkipDates: List<Long>,
    )

    /** How far back [currentItems]' [GoalChecklistItemView.recentSkipDates] looks - a week, matching
     * [com.kevin.legion.ui.common.DeckRange.SEVEN_DAY]'s own default window elsewhere on this
     * screen (`ui/BodyScreen.kt`'s INTAKE/SLEEP panels), so "recent" means the same thing everywhere
     * on this tab. */
    const val RECENT_SKIP_WINDOW_DAYS = 7L

    /**
     * Today's checklist lines, read-only, for [com.kevin.legion.ui.BodyScreen] and the HOME
     * checklist section - never called from a write path. An empty result means no BIO plan has
     * ever been accepted (or every target it produced has since been cleared): [sync] never
     * leaves the checklist non-empty for a [GoalChecklistDay] whose [GoalChecklistDay.hasPlan] is
     * false, so a caller here can read "no rows" as "no plan yet" directly, without re-deriving
     * [GoalChecklistDay] itself - see [GoalChecklist.forToday]'s doc comment for why `hasPlan`
     * true always implies at least one item.
     */
    suspend fun currentItems(context: Context, now: Long = System.currentTimeMillis()): List<GoalChecklistItemView> {
        val db = CarDatabase.getDatabase(context)
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowStart = today.minusDays(RECENT_SKIP_WINDOW_DAYS - 1).atStartOfDay(zone).toInstant().toEpochMilli()

        val list = NotesController.theList(context)
        return NotesController.allRecurringItems(context)
            .filter { it.listId == list.id && it.text.startsWith(ITEM_PREFIX) }
            .sortedBy { it.text }
            .map { item ->
                val skips = db.listItemSkipDao().skippedDatesForItem(item.id)
                GoalChecklistItemView(
                    text = item.text.removePrefix(ITEM_PREFIX),
                    skippedToday = todayStart in skips,
                    recentSkipDates = skips.filter { it >= windowStart }.sortedDescending(),
                )
            }
    }
}
