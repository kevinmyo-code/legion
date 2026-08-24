package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.meals.dayEndEpoch
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.workouts.WorkoutController
import com.kevin.legion.workouts.weekStartEpoch

/**
 * Reconciles the accepted BIO plan onto **the** checklist (`notes/NotesController.theList`) -
 * ticket 04, `goal-plans`, reworked by ticket 06 after ticket 04's own build found its premise
 * false.
 *
 * **Ticket 04 shipped plan lines as RECURRING [ListItem]s and that was wrong.**
 * [NotesController.tick] refuses a recurring item outright (`if (item.repeatKind != null) return
 * false`) - notes-lists-calendar ticket 04, Kevin 2026-08-07: *"a repeat is an event you attend,
 * not a chore you complete."* A recurring plan line was therefore followable and skippable, never
 * tickable, even though "tickable" is the word both Kevin and ticket 04's own prose used. Kevin
 * ruled 2026-08-22, in goal-plans ticket 06: **daily items, not repeats** - over reversing the
 * 2026-08-07 decision and over leaving the checklist skip-only.
 *
 * **What this object writes now: an ordinary ONE-OFF [ListItem] per plan line, per day**,
 * `repeatKind` left null (Room's own default), materialized fresh each day rather than a single
 * recurring row expanded on read. A one-off item is exactly what [NotesController.tick]'s guard
 * lets through, so ticking a plan line works through the pre-existing `manage_item`/
 * [NotesController.tick] path with **no new tool and no second tick path** - ticket 06's own
 * binding rule, unchanged from ticket 04's.
 *
 * **Routed entirely through [NotesController]'s existing writes** - [NotesController.addItem],
 * [NotesController.removeItem] - never a new DAO method and never a new table. Same reasoning as
 * ticket 04's original doc comment: a second write path for [ListItem] is exactly as risky as a
 * second per-occurrence completion column would have been, for the same reason - two places
 * deciding what a checklist line is are two places that can quietly disagree.
 */
object GoalChecklistSync {
    /**
     * The visible marker on every line this object writes. Deliberately visible text, not hidden
     * metadata like [GoalPlanAgent.CONSTRAINT_PREFIX] - a plan line reading "Plan: Hit 2,300 kcal
     * / 180g protein" tells the user where it came from at a glance. It doubles as this object's
     * way of telling a plan line apart from an item the user added by hand sitting on the same
     * single list, so a stale or superseded line can be found without sweeping up anything else.
     */
    const val ITEM_PREFIX = "Plan: "

    /**
     * How long a materialized plan item survives before [materializeToday] trims it, ticked and
     * un-ticked alike - ticket 06's retention call, following [com.kevin.legion.data.local.CONVERSATION_AUDIT_RETENTION_DAYS]'s
     * precedent (a rolling TIME window, trimmed on every write) rather than inventing a third
     * retention convention. **Deleting only the un-ticked items would destroy the denominator** -
     * "what was due" - and leave only what was done, which reads as perfect adherence forever. That
     * is the same class of lie CLAUDE.md's reconciliation gate calls out for a zero standing in for
     * "unknown": inside the window both due and done are present, so a caller can show what was
     * due and what was done; outside it, nothing is claimed at all.
     */
    const val RETENTION_DAYS = 14L

    /**
     * Reads the current [GoalChecklist] lines straight from Room and materializes TODAY's rows:
     * - a derived line with no matching [ITEM_PREFIX] item already created **today** gets one, as
     *   an ordinary one-off [ListItem] via [NotesController.addItem] - `repeatKind` stays null, no
     *   `setRepeat` call, which is the entire fix ticket 06 exists to make;
     * - an item already materialized today whose text is no longer among today's derived lines is
     *   removed (soft-deleted) - a target that changed or was refused on a later acceptance should
     *   not leave a stale line sitting on today's checklist;
     * - an item already materialized today whose text still matches is left completely alone, so
     *   its `done`/`doneAt` state (if the user already ticked it earlier today) survives a
     *   re-acceptance that did not actually change that particular line.
     *
     * **Idempotent by construction.** "Already materialized today" is decided by [ListItem.createdAt]
     * falling on or after [today's local midnight][localDayStart] together with an exact text match
     * against [ITEM_PREFIX] + the derived line - never by a count, which is precisely the shape
     * ticket 06 asked for so that opening the app five times in one day cannot produce five copies
     * of the same line.
     *
     * Called from two places, matching ticket 06's own "materializer that runs on app open, and for
     * the current day at acceptance": `MidnightApplication.onCreate` (every process start) and
     * `service/LiveToolbox.kt`'s `accept_goal_plan` dispatch (immediately after every write
     * `generate_goal_plan`'s flow makes has landed, so a same-day acceptance is reflected on the
     * checklist without waiting for the next app open).
     *
     * Also trims anything outside [RETENTION_DAYS] via [trimExpiredPlanItems] - called here, not
     * from a separate scheduled job, matching [com.kevin.legion.data.local.ConversationAuditDao.record]'s
     * "trim on write" convention rather than adding a second background trigger for the same table.
     */
    suspend fun materializeToday(context: Context, now: Long = System.currentTimeMillis()) {
        val db = CarDatabase.getDatabase(context)
        val mealTarget = db.mealTargetDao().currentTarget(dayStartEpoch(now))
        val sleepTarget = db.sleepTargetDao().currentTarget(dayStartEpoch(now))
        val workoutItems = db.workoutPlanItemDao().currentItems(weekStartEpoch(now))
        // Ticket 08: the whole-plan session count that decides how many days a WEEK each exercise
        // gets shown on (see WorkoutPlan.sessionsPerWeek's own doc and GoalChecklist.forToday's).
        val sessionsPerWeek = db.workoutPlanDao().currentPlan(weekStartEpoch(now))?.sessionsPerWeek

        // The device-local calendar day, not a UTC one (ticket 07) - the same "a timestamp
        // captured from a clock belongs to the day the user was living when it was captured"
        // reasoning `workouts/WorkoutGap.kt`'s own weekStartEpoch doc comment gives, applied to
        // picking which of the week's sessions today actually is.
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).dayOfWeek

        val derived = GoalChecklist.forToday(mealTarget, sleepTarget, workoutItems, sessionsPerWeek, today)
        val wantedTexts = derived.items.map { ITEM_PREFIX + it }.toSet()

        val list = NotesController.theList(context)
        val todayStart = localDayStart(now, 0L)
        val todaysPlanItems = NotesController.allItems(context)
            .filter { it.listId == list.id && it.text.startsWith(ITEM_PREFIX) && it.createdAt >= todayStart }
        val existingTexts = todaysPlanItems.map { it.text }.toSet()

        todaysPlanItems.filter { it.text !in wantedTexts }.forEach { NotesController.removeItem(context, it) }

        wantedTexts.filter { it !in existingTexts }.forEach { text ->
            // Deliberately NOT followed by setRepeat - this is the whole fix. repeatKind stays
            // null (ListItem's own default), which is exactly what makes NotesController.tick
            // accept this item instead of refusing it.
            NotesController.addItem(context, list.id, text)
        }

        sweepPastDayAutoLog(context, list.id, todayStart, now)
        trimExpiredPlanItems(context, list.id, now)
    }

    /**
     * Ticket 08's end-of-day auto-log: every TICKED plan `WORKOUT` line from a PAST day
     * ([ListItem.createdAt] before [todayStart]) that has not yet been logged
     * ([ListItem.loggedAt] still null) is written through [WorkoutController.logSet] - the exact
     * function voice and [com.kevin.legion.ui.body.LogWorkoutSetDialog] both call, so a swept item
     * gets the identical trust tier and write path a spoken log gets (D37, and the ticket's own
     * "same trust tier a spoken log gets - he reported it either way").
     *
     * **Runs on every [materializeToday] call, not just "the first one on a new day"** - the
     * ticket's own idempotence requirement ("no double-log on repeated opens") is what makes this
     * safe rather than merely convenient: a call that finds nothing to sweep (every past-day item
     * already carries a [ListItem.loggedAt]) does no writes at all, so running this every time
     * [materializeToday] runs is equivalent to running it once on the first call of a new day - it
     * is simply cheaper to make the mechanism idempotent than to also track "have I swept today".
     *
     * **Matching is structural, never a parse of the rendered string** (the ticket's own
     * instruction): [GoalChecklist.workoutLinesForDay] is called again, for the ticked item's OWN
     * day and the [com.kevin.legion.data.local.WorkoutPlanItem]/`WorkoutPlan.sessionsPerWeek` rows
     * that were EFFECTIVE THAT WEEK (`currentItems`/`currentPlan` are both "copy forward" reads
     * keyed by week, so this is the exact plan the item was originally materialized against, not
     * whatever plan is current today) - the freshly regenerated line list and the stored item text
     * were built by the SAME function from the SAME data, so an exact string match reliably
     * recovers the `(exercise, sets, reps)` triple with zero parsing. An item whose text no longer
     * matches ANY regenerated line - a meal/sleep line (never produced by [GoalChecklist.workoutLinesForDay]
     * at all), or a workout line whose plan has since changed so the old spread/split no longer
     * reproduces it - is simply skipped: there is nothing here that could safely reconstruct what
     * to log, and skipping is the "no anchor, no claim" posture this codebase uses everywhere else
     * for an unverifiable write.
     *
     * **Never touches `done`/`doneAt`** - only [ListItemDao.markLogged] runs, which the DAO's own
     * doc comment confirms writes `loggedAt` alone. The adherence record (the tick itself) survives
     * a sweep, exactly as ticket 08's own build item requires.
     *
     * **Ticket 09, "one act, one row".** Before writing, this checks
     * [com.kevin.legion.data.local.WorkoutSetLogDao.existingForExerciseInWindow] for [match]'s
     * exercise within the ticked item's OWN local calendar day
     * ([com.kevin.legion.meals.dayStartEpoch]/[com.kevin.legion.meals.dayEndEpoch] of
     * [ListItem.createdAt], not "today" - the item may be several days stale by the time a sweep
     * finally reaches it, and the day that matters is the one the user actually trained). If a set
     * for that exercise already exists anywhere in that window - hand-logged, voice-logged, or
     * even a previous sweep's own row - the tick is adherence ONLY: [ListItemDao.markLogged] still
     * runs (so this candidate is never retried), but [WorkoutController.logSet] is not called at
     * all. A driver who logged three sets of squats by voice and ticked the same line that evening
     * did one workout, not two. When a real write does happen, it carries the item's own id as
     * [com.kevin.legion.data.local.WorkoutSetLog.sourceListItemId] - see that column's doc for what
     * reads it back.
     */
    private suspend fun sweepPastDayAutoLog(context: Context, listId: Long, todayStart: Long, now: Long) {
        val candidates = NotesController.allItems(context).filter {
            it.listId == listId && it.text.startsWith(ITEM_PREFIX) &&
                it.createdAt < todayStart && it.done && it.loggedAt == null
        }
        if (candidates.isEmpty()) return

        val db = CarDatabase.getDatabase(context)
        val zone = java.time.ZoneId.systemDefault()
        for (item in candidates) {
            val itemDay = java.time.Instant.ofEpochMilli(item.createdAt).atZone(zone).dayOfWeek
            val itemWeekStart = weekStartEpoch(item.createdAt)
            val workoutItems = db.workoutPlanItemDao().currentItems(itemWeekStart)
            if (workoutItems.isEmpty()) continue
            val sessionsPerWeek = db.workoutPlanDao().currentPlan(itemWeekStart)?.sessionsPerWeek
            val lines = GoalChecklist.workoutLinesForDay(workoutItems, sessionsPerWeek, itemDay)
            val strippedText = item.text.removePrefix(ITEM_PREFIX)
            val match = lines.firstOrNull { it.text == strippedText } ?: continue

            // Ticket 09: one act, one row. A set for this exercise already logged - by hand, by
            // voice, or by an earlier sweep - anywhere in the ITEM's own local day means the user
            // already reported doing it; this tick is adherence, not a second occurrence, so
            // stamp loggedAt (never retry this candidate again) and write nothing.
            val itemDayStart = dayStartEpoch(item.createdAt)
            val itemDayEnd = dayEndEpoch(item.createdAt)
            val alreadyLogged = db.workoutSetLogDao().existingForExerciseInWindow(match.exercise, itemDayStart, itemDayEnd)
            if (alreadyLogged != null) {
                NotesController.markLogged(context, item.id, now)
                continue
            }

            // Timestamped to the ITEM's own day (ticket 08: "not now") - a set logged by tonight's
            // app-open still reads as having happened the day it was ticked for. sourceListItemId
            // links this row back to the item so an untick can find and delete it (ticket 09).
            val outcome = WorkoutController.logSet(
                context = context,
                exercise = match.exercise,
                sets = match.sets,
                reps = match.reps,
                weightValue = null,
                weightUnit = null,
                loggedAt = item.createdAt,
                sourceListItemId = item.id,
            )
            // A failed write leaves loggedAt null, so a LATER sweep retries it - the same "no false
            // success" posture CLAUDE.md §7 asks of every write, applied to a write nobody asked
            // for out loud but that this object is making on the user's behalf.
            if (outcome.success) NotesController.markLogged(context, item.id, now)
        }
    }

    /**
     * Soft-deletes every plan item older than [RETENTION_DAYS], regardless of `done` - the ticket
     * 06 retention rule read literally: ticked and un-ticked items are removed TOGETHER, so the
     * window never degrades into a record of only what was completed. [NotesController.removeItem]
     * is reused rather than a bespoke bulk-delete query, for the same "no second write path" reason
     * [materializeToday]'s own doc comment gives - it also cancels any pending alarm, harmless here
     * since a plan item never carries one.
     */
    private suspend fun trimExpiredPlanItems(context: Context, listId: Long, now: Long) {
        val cutoff = now - RETENTION_DAYS * 24 * 60 * 60 * 1000
        NotesController.allItems(context)
            .filter { it.listId == listId && it.text.startsWith(ITEM_PREFIX) && it.createdAt < cutoff }
            .forEach { NotesController.removeItem(context, it) }
    }

    /**
     * The device-local-date start-of-day for TODAY plus [daysAgo] days back, expressed as epoch
     * millis - `localDayStart(now, 0)` is today's own midnight, `localDayStart(now, 6)` is the
     * midnight six days ago. Shared by [materializeToday]'s "already materialized today" check and
     * [currentItems]' "today's rows"/"trailing window" reads, so none of them can quietly disagree
     * about where midnight falls - the same reason [GoalChecklistSync]'s predecessor computed this
     * inline in two places and this build folds it into one.
     */
    private fun localDayStart(now: Long, daysAgo: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.LocalDate.now(zone).minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * One checklist line as read back for a screen - [text] with [ITEM_PREFIX] already stripped
     * (a screen shows "Hit 2,300 kcal...", not "Plan: Hit 2,300 kcal..."), plus its real
     * per-occurrence completion state, which - unlike ticket 04's shipped recurring version - now
     * genuinely exists, because this is an ordinary one-off [ListItem] with its own `done`/`doneAt`.
     *
     * [recentCompletionDates] is a record of [ListItem.doneAt] timestamps for every PAST day's
     * materialization of this same line, within [RECENT_COMPLETION_WINDOW_DAYS] - **shown, never
     * scored** (CLAUDE.md §7, ticket 06's own "adherence becomes truthful... still shown, never
     * scored"). A caller must not turn this into a percentage or a streak; the honest sentence this
     * data supports is "here is what was done", not "here is a grade".
     */
    data class GoalChecklistItemView(
        val text: String,
        val done: Boolean,
        val doneAt: Long?,
        /** Most recent first, `doneAt` timestamps from other days' materializations of this same
         * line within the last [RECENT_COMPLETION_WINDOW_DAYS] days - see the class doc. */
        val recentCompletionDates: List<Long>,
        /** The underlying [ListItem.id] - ticket 07's tick box needs it to call [toggle], the same
         * `manage_item`/[NotesController.tick] path a spoken tick already goes through. Not shown
         * to the user; a screen-plumbing detail only. */
        val id: Long = 0L,
    )

    /** How far back [currentItems]' [GoalChecklistItemView.recentCompletionDates] looks - a week,
     * matching [com.kevin.legion.ui.common.DeckRange.SEVEN_DAY]'s own default window elsewhere on
     * this screen (`ui/BodyScreen.kt`'s INTAKE/SLEEP panels), so "recent" means the same thing
     * everywhere on this tab. Same value ticket 04 shipped for its (since-replaced) skip window. */
    const val RECENT_COMPLETION_WINDOW_DAYS = 7L

    /**
     * Today's checklist lines, read-only, for [com.kevin.legion.ui.BodyScreen] and the HOME
     * checklist section - never called from a write path. An empty result almost always means no
     * BIO plan has ever been accepted (or every target it produced has since been cleared).
     *
     * **One narrow exception since ticket 07's day slotting.** [GoalChecklistDay.hasPlan] no longer
     * implies at least one item: a workout-ONLY plan, on a day the deterministic spread assigns no
     * session to, is a real accepted plan whose today-window is genuinely empty too. A caller
     * rendering "no plan yet" for that case is a rare cosmetic wrong sentence, not a data-loss bug -
     * the accepted plan is still on file and still governs every OTHER day of the week - so it is
     * accepted here rather than threading `hasPlan` through every screen that reads this list.
     *
     * **Does not call [materializeToday] itself.** This stays a plain read, matching ticket 04's
     * original split of "the write happens at acceptance/app-open, this function only reads back
     * whatever is currently on the list" - a screen composing is not a write trigger.
     */
    suspend fun currentItems(context: Context, now: Long = System.currentTimeMillis()): List<GoalChecklistItemView> {
        val list = NotesController.theList(context)
        val allPlanItems = NotesController.allItems(context)
            .filter { it.listId == list.id && it.text.startsWith(ITEM_PREFIX) }

        val todayStart = localDayStart(now, 0L)
        val windowStart = localDayStart(now, RECENT_COMPLETION_WINDOW_DAYS - 1)
        val todaysItems = allPlanItems
            .filter { it.createdAt >= todayStart }
            .sortedBy { it.text }
        val windowItems = allPlanItems.filter { it.createdAt >= windowStart }

        return todaysItems.map { item ->
            val completions = windowItems
                .filter { it.text == item.text && it.id != item.id && it.done && it.doneAt != null }
                .mapNotNull { it.doneAt }
                .sortedDescending()
            GoalChecklistItemView(
                text = item.text.removePrefix(ITEM_PREFIX),
                done = item.done,
                doneAt = item.doneAt,
                recentCompletionDates = completions,
                id = item.id,
            )
        }
    }

    /**
     * Ticks or unticks the plan line at [id] - ticket 07's tick box, and the reason it can exist
     * at all: this is [NotesController.tick]/[NotesController.untick] called DIRECTLY, the exact
     * pair `service/LiveToolbox.kt`'s `manage_item` dispatch calls for a spoken `tick`/`untick`
     * (see that file's `"tick" ->`/`"untick" ->` branches). Ticket 04's "do not build a second
     * ticking path" rule forbids a second MECHANISM - a parallel store, a different notion of
     * done - not a second caller of the one mechanism that already exists; a checkbox that reaches
     * this function is the same path with a finger on it, which ADR 0035 now makes mandatory
     * rather than merely permitted: a checklist tickable only by voice fails in exactly the moment
     * a checklist gets used, at the gym, with no wake word available.
     *
     * A missing or already-removed [id] is a silent no-op - [currentItems] only ever shows ids
     * that existed at load time, and a screen reloading after this call sees whatever is actually
     * on Room now, never a value this function invented.
     */
    suspend fun toggle(context: Context, id: Long) {
        val item = NotesController.itemById(context, id) ?: return
        if (item.listId != NotesController.theList(context).id) return
        if (item.done) NotesController.untick(context, item) else NotesController.tick(context, item)
    }
}
