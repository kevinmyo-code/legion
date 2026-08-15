package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kevin.legion.plan.TrustTier

/**
 * A single bodyweight measurement (`.scratch/legion-shape/issues/08-workouts-domain.md` D23):
 * "Bodyweight is its OWN thing, a plain reported measurement with its own target later. Not a
 * field on the workout log." Kevin named workouts and weight together in the same request; they
 * are recorded separately here because one is an activity (a set performed) and the other is a
 * measurement (a state of the body) - conflating them onto one row would make "how much weight
 * did you lift" and "how much do you weigh" the same column, which they are not.
 *
 * [trustTier] is always [TrustTier.REPORTED] for a voice-logged reading, same reasoning as
 * [WorkoutSetLog.trustTier] - stored explicitly rather than assumed, per ticket 05 D4.
 *
 * No target table exists for bodyweight yet ("its own target later" - D23's own words defer it);
 * this entity is deliberately just the measurement.
 */
@Entity(tableName = "bodyweight_logs")
data class BodyweightLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weightValue: Double,
    /** "lbs" or "kg". */
    val weightUnit: String,
    val loggedAt: Long,
    val trustTier: TrustTier,
)
