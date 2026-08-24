package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [MutedReminder]. Thin on purpose - see that entity's own doc comment
 * for why muting deliberately bypasses [com.kevin.legion.engine.RecordStore]'s write door. */
@Dao
interface MutedReminderDao {
    /** Idempotent - silencing an already-silenced record just refreshes [MutedReminder.mutedAt]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mute(row: MutedReminder)

    @Query("DELETE FROM muted_reminders WHERE recordId = :recordId")
    suspend fun unmute(recordId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM muted_reminders WHERE recordId = :recordId)")
    suspend fun isMuted(recordId: Long): Boolean

    /** Every id in [ids] that is currently muted - a single batched lookup for
     * [com.kevin.legion.engine.dates.DatesAgenda] rather than one query per candidate record. */
    @Query("SELECT recordId FROM muted_reminders WHERE recordId IN (:ids)")
    suspend fun mutedRecordIds(ids: List<Long>): List<Long>
}
