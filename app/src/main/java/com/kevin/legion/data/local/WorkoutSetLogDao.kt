package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [WorkoutSetLog]. */
@Dao
interface WorkoutSetLogDao {
    /** Returns the new row's id, so a same-turn `undo_last_log` (ticket 11 D36) can name it. */
    @Insert
    suspend fun insert(log: WorkoutSetLog): Long

    @Query("SELECT * FROM workout_set_logs ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<WorkoutSetLog>

    /** Every set logged within [fromMs, toMs) - the window [com.kevin.legion.workouts.WorkoutController.weekGap] sums to compute "sessions done this week" (D24). */
    @Query("SELECT * FROM workout_set_logs WHERE loggedAt >= :fromMs AND loggedAt < :toMs")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<WorkoutSetLog>

    /** The single most recent row across the whole table, for `undo_last_log`. */
    @Query("SELECT * FROM workout_set_logs ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): WorkoutSetLog?

    /**
     * One row of [distinctExercisesByRecency]'s projection: an exercise name plus the most recent
     * [WorkoutSetLog.loggedAt] logged under it. A plain data class rather than a new `@Entity` -
     * Room maps a raw `@Query` projection onto any class whose fields match the selected column
     * names, and this one is read-only and never persisted on its own.
     */
    data class ExerciseRecency(val exercise: String, val lastLoggedAt: Long)

    /**
     * TRAINING drilldown's exercise-list level (ticket 16 - "panel -> exercise list, distinct
     * exercises, most recent first"). `GROUP BY exercise` collapses every set-group logged under
     * the same name into one row, `MAX(loggedAt)` picks that exercise's most recent set, and the
     * outer `ORDER BY` sorts the list by that - so "Squat" logged an hour ago sorts above "Pushups"
     * logged three days ago without a second query per exercise.
     */
    @Query("SELECT exercise, MAX(loggedAt) AS lastLoggedAt FROM workout_set_logs GROUP BY exercise ORDER BY lastLoggedAt DESC")
    suspend fun distinctExercisesByRecency(): List<ExerciseRecency>

    /**
     * Every set ever logged under [exercise], OLDEST first - the per-exercise progression
     * drilldown's raw source. The session-day bucketing (one point per day, MAX weight that day)
     * is a pure function ([com.kevin.legion.ui.buildExerciseProgression]), not this query - this
     * stays a plain unfiltered read so the pure layer can be unit-tested against fixed fixtures.
     */
    @Query("SELECT * FROM workout_set_logs WHERE exercise = :exercise ORDER BY loggedAt ASC")
    suspend fun forExercise(exercise: String): List<WorkoutSetLog>

    @Query("DELETE FROM workout_set_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
