package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: LibraryTrack): Long

    /** The whole drawer, newest import first. */
    @Query("SELECT * FROM library_track ORDER BY addedAt DESC")
    suspend fun getAll(): List<LibraryTrack>

    @Query("SELECT * FROM library_track WHERE id = :id")
    suspend fun getById(id: Long): LibraryTrack?

    /** Row only - callers must also delete the backing file and any mixtape_track rows. */
    @Query("DELETE FROM library_track WHERE id = :id")
    suspend fun delete(id: Long)
}
