package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DriveReassignmentDao {
    /** Returns the row id Room assigned - [com.kevin.legion.vehicle.FleetEngineStore.recordDriveReassignment]
     * reads it back to push the row to the server without a second [getBySyncId] round trip. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reassignment: DriveReassignment): Long

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

    /** By the local autoincrement id - same role [DriveDao.getById] plays for [Drive]. */
    @Query("SELECT * FROM drive_reassignments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DriveReassignment?

    /** Records the server's uuid after a first successful push - see [DriveReassignment.serverId]'s
     * own doc comment for why this is bookkeeping only, never consulted for identity. */
    @Query("UPDATE drive_reassignments SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)
}
