package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The wellbeing digest's schedule - goal-plans ticket 05, `.scratch/goal-plans/issues/05-wellbeing-digest.md`.
 *
 * **A one-row singleton, matching [SitrepSchedule]'s own shape and reasoning exactly** rather than
 * widening that table: `sitrep_schedule` carries a newsletter sender list this domain has no
 * concept of, and a `moduleKey` column smuggled onto a table that means something else for another
 * feature is exactly the "two things pretending to be one" shape this project's Room layer avoids
 * elsewhere (see [SitrepSchedule]'s own doc for why it did not become columns on
 * [SitrepModuleSetting]). A second one-row table says plainly "this is the one schedule for this
 * one raise," nothing more.
 *
 * **`hour`/`minute` are plain ints in device-local time**, [SitrepSchedule]'s own convention -
 * "8am every morning," not an epoch moment that goes stale the instant
 * [com.kevin.legion.wellbeing.WellbeingDigestScheduler] re-arms it for the next day.
 *
 * No `enabled` column: a null row (nothing ever written) already means "no schedule", the same way
 * [SitrepSchedule] has no `enabled` column and null already means "the sitrep is askable but never
 * fires on its own." A schedule with nothing scheduled is not a fact worth a second boolean.
 */
@Entity(tableName = "wellbeing_digest_schedule")
data class WellbeingDigestSchedule(
    @PrimaryKey val id: Int = ID,
    val hour: Int,
    val minute: Int,
) {
    companion object {
        /** The one and only row this table ever holds - see [SitrepSchedule.ID]'s own doc. */
        const val ID = 0
    }
}

@Dao
interface WellbeingDigestScheduleDao {
    @Query("SELECT * FROM wellbeing_digest_schedule WHERE id = ${WellbeingDigestSchedule.ID} LIMIT 1")
    suspend fun get(): WellbeingDigestSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(schedule: WellbeingDigestSchedule)
}
