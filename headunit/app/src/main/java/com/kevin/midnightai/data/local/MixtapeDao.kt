package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MixtapeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mixtape: Mixtape): Long

    @Update
    suspend fun update(mixtape: Mixtape)

    /** The shelf of tapes, in driver-arranged order. */
    @Query("SELECT * FROM mixtape ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<Mixtape>

    @Query("SELECT * FROM mixtape WHERE id = :id")
    suspend fun getById(id: Long): Mixtape?

    /** Row only - callers must also clear its mixtape_track rows (kept tracks stay in the library). */
    @Query("DELETE FROM mixtape WHERE id = :id")
    suspend fun delete(id: Long)
}
