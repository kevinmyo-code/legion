package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [PlaceReminder].
 */
@Dao
interface PlaceReminderDao {
    @Insert
    suspend fun insert(reminder: PlaceReminder)

    @Query("SELECT * FROM place_reminders WHERE placeLabel = :label AND done = 0 ORDER BY createdAt")
    suspend fun activeForPlace(label: String): List<PlaceReminder>

    @Query("SELECT * FROM place_reminders WHERE done = 0 ORDER BY createdAt")
    suspend fun allActive(): List<PlaceReminder>

    // updatedAt bumped in SQL so cross-device sync LWW (S1) sees the done-toggle
    // as a fresh write (see CarTaskDao.markDone).
    @Query("UPDATE place_reminders SET done = 1, updatedAt = (strftime('%s','now') * 1000) WHERE id = :id")
    suspend fun markDone(id: Long)
}
