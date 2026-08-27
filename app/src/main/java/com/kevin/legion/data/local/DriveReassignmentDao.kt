package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DriveReassignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reassignment: DriveReassignment)

    /**
     * Oldest first: rules are applied in [DriveReassignment.updatedAt] order so a
     * later correction of the same drive lands last and wins.
     */
    @Query("SELECT * FROM drive_reassignments ORDER BY updatedAt ASC")
    suspend fun getAll(): List<DriveReassignment>

    /**
     * Drops rules older than [cutoff]. Mirrors the B19 tombstone horizon (90 days)
     * and for the same reason: a rule has to outlive any device that might still
     * resurrect old-keyed rows from Drive and need re-keying. A device offline
     * longer than the horizon keeps its misattribution - accepted, see
     * library/decisions.md 2026-07-16.
     */
    @Query("DELETE FROM drive_reassignments WHERE updatedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    /** Looks a reassignment rule up by its portable [DriveReassignment.syncId] - the
     * insert-if-absent replica check [com.kevin.legion.backend.FleetReconcile] uses, same role
     * `CodeEventDao.getBySyncId` plays for `CodeEvent`. */
    @Query("SELECT * FROM drive_reassignments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): DriveReassignment?
}
