package com.kevin.legion.data.local

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
)
