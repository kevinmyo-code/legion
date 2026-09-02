package com.kevin.legion.goals

import android.content.Context
import com.kevin.legion.backend.LastAspectsWriteThrough
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import java.util.UUID

/**
 * The single write/read path for [Goal] - ticket 19's "a small controller ... so the tool layer
 * and the screen cannot drift" requirement. The Live voice tools (`set_goal`/`list_goals`/
 * `close_goal` in [com.kevin.legion.service.LiveToolbox]) and the GOALS panel
 * (`com.kevin.legion.ui.goals.GoalsPanel`) both call in here; neither ever touches
 * [com.kevin.legion.data.local.GoalDao] directly, matching every other aspect's controller/DAO
 * split ([com.kevin.legion.meals.MealController], [com.kevin.legion.workouts.WorkoutController]).
 *
 * This file adds no new GOAL RULES of its own - every rule below is carried over verbatim from
 * `.scratch/aspect-advisors/issues/02-goal-store.md`'s Answer and [Goal]'s own doc comment. What
 * this file owns is the one place those rules get APPLIED, so voice and screen can never disagree
 * about what counts as a revision versus a new goal.
 */
object GoalController {

    /**
     * The four aspects a goal can be set against. Matches
     * [com.kevin.legion.advisor.AdvisorAspect]'s `key` values minus `home` - HOME is a cross-aspect
     * synthesis view (ticket 09) with no goals of its own, so nothing here ever writes
     * `aspect = "home"` and neither voice tool's declaration offers it as a choice.
     *
     * Plain [String] constants, not a reference to [com.kevin.legion.advisor.AdvisorAspect] itself
     * - `advisor/` is mid-build by two other agents this wave (ticket 19's own file-exclusivity
     * note) and this controller must not carry a compile dependency on a package it was told not to
     * touch. [Goal.aspect] is TEXT with no CHECK constraint for exactly this reason (see [Goal]'s
     * class doc, "nothing here should force a schema bump just to teach the store about a new
     * aspect name") - duplicating the four literal strings here rather than importing the enum is
     * the same posture applied one layer up.
     */
    val ASPECTS = listOf("bio", "log", "fleet", "cred")

    /**
     * What [setGoal] actually did. The caller (tool dispatch, or the panel's edit dialog) phrases
     * its own confirmation off this rather than re-deriving "was this a revision" by comparing the
     * two [Goal] rows itself.
     */
    sealed class SetOutcome {
        data class Created(val goal: Goal) : SetOutcome()
        data class Revised(val goal: Goal, val previous: Goal) : SetOutcome()
        /** Every field the caller supplied already matched the current row - see [setGoal]'s
         * "material change" doc. No row was written, [goal] is the unchanged current row. */
        data class Unchanged(val goal: Goal) : SetOutcome()
    }

    /** [closeGoal] found no active goal matching the caller's text, or found more than one. */
    sealed class CloseOutcome {
        data class Closed(val goal: Goal) : CloseOutcome()
        object NotFound : CloseOutcome()
        data class Ambiguous(val matches: List<Goal>) : CloseOutcome()
    }

    /**
     * Creates a new goal, or REVISES an existing one - [Goal]'s house copy-forward pattern (answer
     * call 4): nothing is ever updated in place except [status]/[Goal.closedAt] (see [closeByLineage]).
     *
     * Two ways a caller identifies "this is a revision, not a new goal":
     * - [revises] - the exact row being edited, passed by the GOALS panel's edit dialog, which
     *   already has the [Goal] on screen. Never ambiguous.
     * - `null` [revises] with a non-null [metricKey] - the voice path (`set_goal` carries no
     *   lineage id to pass). If [aspect] already has an ACTIVE goal tracking the same [metricKey],
     *   THAT is the row being revised - "save $30k by 2028" restated with a new number is the same
     *   metric, not a second goal. A `null` [metricKey] with no [revises] always mints a BRAND-NEW
     *   goal: there is no other reliable natural key to match voice-only prose goals against, and
     *   guessing off statement-text similarity risks silently merging two goals that only happen to
     *   share a few words.
     *
     * A revision is only written when something actually differs. [Goal]'s doc comment names
     * [statement]/[targetValue]/[unit]/[deadlineEpoch] as MATERIAL; this widens that by one field on
     * purpose - a change to [metricKey] alone (the goal quietly gets re-pointed at a different
     * measured value while the headline stays put) is exactly the kind of silent shift the revision
     * trail exists to catch, so it counts here too. Saving an edit dialog with nothing actually
     * changed is a no-op, not a spurious revision row - see [SetOutcome.Unchanged].
     */
    suspend fun setGoal(
        context: Context,
        aspect: String,
        statement: String,
        targetValue: Double? = null,
        unit: String? = null,
        metricKey: String? = null,
        deadlineEpoch: Long? = null,
        revises: Goal? = null,
    ): SetOutcome {
        val dao = CarDatabase.getDatabase(context).goalDao()
        val target = revises
            ?: metricKey?.let { key -> dao.currentGoals(aspect).firstOrNull { it.metricKey == key } }

        if (target == null) {
            val goal = Goal(
                // No mutation exists on GoalDao to fix up a self-referential lineageId after
                // insert, deliberately - GoalDao's own doc comment: "close() is the only in-place
                // mutation this DAO performs". So a brand-new lineage mints its OWN id here rather
                // than trying to reuse the row's future autogenerated id (which would need a second
                // write to fix up). UUID.randomUUID().leastSignificantBits - the same
                // "practically-unique, no coordination needed" posture [Goal.syncId] already uses
                // one field below [Goal.lineageId] in the entity, just a Long instead of a String.
                // A negative value is harmless: lineageId is only ever a grouping key this app reads
                // back by equality, never a value shown to Kevin or compared as a magnitude.
                lineageId = UUID.randomUUID().leastSignificantBits,
                aspect = aspect,
                statement = statement,
                targetValue = targetValue,
                unit = unit,
                metricKey = metricKey,
                deadlineEpoch = deadlineEpoch,
            )
            // live-sync ticket: write-through, not a bare dao.insert - pushes to the server (or
            // enqueues in sync_outbox on failure) the moment this goal is created locally. See
            // LastAspectsWriteThrough's own class doc.
            val stored = LastAspectsWriteThrough.addGoal(context, goal)
            return SetOutcome.Created(stored)
        }

        val materialChange = target.statement != statement ||
            target.targetValue != targetValue ||
            target.unit != unit ||
            target.deadlineEpoch != deadlineEpoch ||
            target.metricKey != metricKey
        if (!materialChange) return SetOutcome.Unchanged(target)

        val revision = Goal(
            lineageId = target.lineageId,
            aspect = aspect,
            statement = statement,
            targetValue = targetValue,
            unit = unit,
            metricKey = metricKey,
            deadlineEpoch = deadlineEpoch,
            supersedesId = target.id,
        )
        // live-sync ticket: write-through, same reasoning as the Created branch above.
        val stored = LastAspectsWriteThrough.addGoal(context, revision)
        return SetOutcome.Revised(stored, target)
    }

    /** Every currently-active goal for [aspect] - the panel's read, and `list_goals`'s single-aspect form. */
    suspend fun currentGoals(context: Context, aspect: String): List<Goal> =
        CarDatabase.getDatabase(context).goalDao().currentGoals(aspect)

    /** Every currently-active goal across every aspect - `list_goals`'s no-aspect form. */
    suspend fun allCurrentGoals(context: Context): List<Goal> =
        CarDatabase.getDatabase(context).goalDao().allCurrentGoals()

    /**
     * Closes the one active goal in [aspect] whose statement matches [query] - case-insensitive,
     * either string containing the other, so "savings" matches "save $30k by 2028" and vice versa.
     * A blank [query] never matches anything (it would otherwise match every active goal at once).
     *
     * This text match exists ONLY for the voice path (`close_goal` carries no id, same reasoning as
     * [setGoal]'s [metricKey] match). The panel's own close button bypasses it entirely and calls
     * [closeByLineage] with the exact row already on screen - never ambiguous, because there is
     * nothing to search for.
     */
    suspend fun closeGoal(context: Context, aspect: String, query: String, status: String = "achieved"): CloseOutcome {
        val q = query.trim().lowercase()
        if (q.isBlank()) return CloseOutcome.NotFound
        val candidates = currentGoals(context, aspect).filter {
            val s = it.statement.lowercase()
            s.contains(q) || q.contains(s)
        }
        return when (candidates.size) {
            0 -> CloseOutcome.NotFound
            1 -> {
                closeByLineage(context, candidates.single().lineageId, status)
                CloseOutcome.Closed(candidates.single())
            }
            else -> CloseOutcome.Ambiguous(candidates)
        }
    }

    /**
     * The panel's close path, and what [closeGoal] delegates to once it has resolved exactly one
     * lineage. Thin wrapper over [com.kevin.legion.data.local.GoalDao.close] so both callers go
     * through one function even for the DAO's own single in-place mutation.
     */
    suspend fun closeByLineage(context: Context, lineageId: Long, status: String, closedAt: Long = System.currentTimeMillis()) {
        // live-sync ticket: write-through, not a bare dao.close - pushes the closed row to the
        // server (or enqueues on failure) right after the local in-place UPDATE. See
        // LastAspectsWriteThrough.closeGoal's own doc comment.
        LastAspectsWriteThrough.closeGoal(context, lineageId, status, closedAt)
    }
}
