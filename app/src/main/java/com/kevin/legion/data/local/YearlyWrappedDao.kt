package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface YearlyWrappedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wrapped: YearlyWrapped)

    @Delete
    suspend fun delete(wrapped: YearlyWrapped)

    @Query("SELECT * FROM yearly_wrapped WHERE vehicleId = :vehicleId AND year = :year LIMIT 1")
    suspend fun getForYear(vehicleId: String, year: Int): YearlyWrapped?

    /** All of a vehicle's Wrapped years, newest first - for the shelf. */
    @Query("SELECT * FROM yearly_wrapped WHERE vehicleId = :vehicleId ORDER BY year DESC")
    suspend fun getAll(vehicleId: String): List<YearlyWrapped>
}
