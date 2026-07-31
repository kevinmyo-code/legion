package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ForesightNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: ForesightNote): Long

    @Query("SELECT * FROM foresight_notes WHERE vehicleId = :vehicleId AND dismissed = 0 ORDER BY generatedAt DESC")
    suspend fun getActive(vehicleId: String): List<ForesightNote>

    @Query("UPDATE foresight_notes SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    @Query("DELETE FROM foresight_notes WHERE generatedAt < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)
}
