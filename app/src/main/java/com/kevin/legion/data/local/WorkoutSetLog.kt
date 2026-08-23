package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kevin.legion.plan.TrustTier

/**
 * One logged workout set-group (`.scratch/legion-shape/issues/08-workouts-domain.md` D22):
 * "A logged workout is per SET. 'Three sets of squats at 225' has to land in one breath, because
 * voice is the primary way in." One row is the SETS that share the same exercise/reps/weight in a
 * single utterance, not one row per individual set - a driver who does three sets of squats at
 * 225 says it once and it lands once, with [sets] = 3.
 *
 * [reps]/[weightValue]/[weightUnit] are all nullable: D35 (ticket 11) says partial voice input
 * "stores what it has and asks once for the important missing piece" - [exercise] and [sets] are
 * that important piece (declared `required` on the `log_workout_set` tool in
 * [com.kevin.legion.service.LiveToolbox]), everything else is optional detail a driver may not
 * have said ("I did three sets of pushups" has no weight at all).
 *
 * [trustTier] (ticket 05 D4 / ticket 11 D37): every log entry records which tier it is in, stored
 * rather than derived later. Every row here is [TrustTier.REPORTED] by construction - a voice-
 * logged set has no outside source to check it against, the same reasoning
 * [com.kevin.legion.data.local.IngestMethod.UNRECONCILED] carries for a ledger row with no anchor
 * to reconcile against. The column exists (rather than being hardcoded as a doc comment) so a
 * future non-voice ingestion path (a wearable's rep counter, say) has somewhere to record
 * [TrustTier.PROVEN] without a schema change.
 *
 * [sourceListItemId] (v33, goal-plans ticket 09 - "the log knows where it came from"): the
 * [com.kevin.legion.data.local.ListItem.id] of the ticked plan line that produced THIS row, when
 * one did - null for every hand- or voice-logged set, exactly as before this column existed.
 * Written once, by [com.kevin.legion.advisor.GoalChecklistSync]'s end-of-day sweep at the moment
 * it calls [com.kevin.legion.workouts.WorkoutController.logSet], and read back in exactly two
 * places: [com.kevin.legion.notes.NotesController.untick], which deletes any row carrying the
 * unticked item's id so a correction propagates to the training history (ticket 09's defect 1 -
 * before this column existed there was nothing to find, so an untick could never reach the log at
 * all); and the sweep's own "one act, one row" dedup check, which is a plain exercise+day match
 * and does NOT read this column (a hand-logged set has no id to match against in the first place -
 * see [com.kevin.legion.advisor.GoalChecklistSync.sweepPastDayAutoLog]'s doc for why the dedup has
 * to work independently of this link). **No `@ForeignKey`** - deliberately, matching
 * [com.kevin.legion.data.local.LedgerTransaction.sourceFileId]'s precedent: a `ListItem` is
 * soft-deleted, never hard-deleted, so the id this column names never actually goes dangling, and
 * a foreign key here would only add ON DELETE ceremony this schema's no-CHECK-constraints,
 * no-unnecessary-FK posture (ticket 01) does not otherwise use.
 */
@Entity(tableName = "workout_set_logs")
data class WorkoutSetLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exercise: String,
    val sets: Int,
    val reps: Int? = null,
    val weightValue: Double? = null,
    /** "lbs" or "kg", null when no weight was stated (e.g. a bodyweight exercise). */
    val weightUnit: String? = null,
    val loggedAt: Long,
    val trustTier: TrustTier,
    /** See the class doc comment - null for every hand/voice log, the originating [ListItem.id]
     * for a swept one. */
    @ColumnInfo(defaultValue = "NULL") val sourceListItemId: Long? = null,
)
