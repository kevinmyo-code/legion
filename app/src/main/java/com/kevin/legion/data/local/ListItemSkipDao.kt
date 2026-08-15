package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [ListItemSkip]. */
@Dao
interface ListItemSkipDao {
    @Insert
    suspend fun insert(skip: ListItemSkip): Long

    @Query("SELECT skippedDate FROM list_item_skips WHERE itemId = :itemId AND deleted = 0")
    suspend fun skippedDatesForItem(itemId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM list_item_skips WHERE itemId = :itemId AND skippedDate = :date AND deleted = 0")
    suspend fun existsForDate(itemId: Long, date: Long): Int
}
