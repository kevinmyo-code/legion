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

    /** Every non-tombstoned skip row - cutover 1's one-time rekey pass reads this to walk every
     * row and re-point [ListItemSkip.itemId] at its item's new engine [EngineRecord.id] (see
     * `engine/migration/EngineDataMigrationWave1.rekeySkipsToEngineIdsOnce`'s own doc comment for
     * why [itemId] itself needs no schema change - it is, and always was, "whatever id the active
     * item store currently assigns this row's item," and cutover simply changes which store that
     * is). */
    @Query("SELECT * FROM list_item_skips WHERE deleted = 0")
    suspend fun allActive(): List<ListItemSkip>

    /** Re-points one skip row at [newItemId] - the one write cutover 1's rekey pass makes, never
     * called outside that one-time pass. */
    @Query("UPDATE list_item_skips SET itemId = :newItemId, updatedAt = :at WHERE id = :id")
    suspend fun rekeyItemId(id: Long, newItemId: Long, at: Long)
}
