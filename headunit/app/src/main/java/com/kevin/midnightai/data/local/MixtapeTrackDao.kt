package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MixtapeTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MixtapeTrack): Long

    /** Membership rows for one tape, in track order - join against LibraryTrackDao for display. */
    @Query("SELECT * FROM mixtape_track WHERE mixtapeId = :mixtapeId ORDER BY sortOrder ASC")
    suspend fun forMixtape(mixtapeId: Long): List<MixtapeTrack>

    /** Which tapes (membership rows) a library track currently belongs to. */
    @Query("SELECT * FROM mixtape_track WHERE libraryTrackId = :libraryTrackId")
    suspend fun forLibraryTrack(libraryTrackId: Long): List<MixtapeTrack>

    @Query("SELECT MAX(sortOrder) FROM mixtape_track WHERE mixtapeId = :mixtapeId")
    suspend fun maxSortOrder(mixtapeId: Long): Int?

    @Query("UPDATE mixtape_track SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** Removes one track from one tape - the library row and file are untouched. */
    @Query("DELETE FROM mixtape_track WHERE mixtapeId = :mixtapeId AND libraryTrackId = :libraryTrackId")
    suspend fun deleteMembership(mixtapeId: Long, libraryTrackId: Long)

    /** All membership for a tape being deleted entirely. */
    @Query("DELETE FROM mixtape_track WHERE mixtapeId = :mixtapeId")
    suspend fun deleteAllForMixtape(mixtapeId: Long)

    /** All membership for a library track being deleted entirely (out of every tape). */
    @Query("DELETE FROM mixtape_track WHERE libraryTrackId = :libraryTrackId")
    suspend fun deleteAllForLibraryTrack(libraryTrackId: Long)
}
