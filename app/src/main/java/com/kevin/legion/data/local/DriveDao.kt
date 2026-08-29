package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * No update, no delete - a finalised [Drive] is an append-only fact, same posture as
 * [CodeClearEventDao]/[OdbSampleDao]'s sync-relevant tables. See [Drive]'s own doc comment.
 */
@Dao
interface DriveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(drive: Drive): Long

    /** Newest first, capped at [limit] - what a future post-drive-summary or history surface reads. */
    @Query("SELECT * FROM drives WHERE vehicleId = :vehicleId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(vehicleId: String, limit: Int): List<Drive>

    /** Every drive on file - what [com.kevin.legion.backend.FleetReconcile] reads as the upload
     * source. `drives` has no engine-record counterpart (backend-erp ticket 06's ruling: drives are
     * not engine records), so unlike [com.kevin.legion.data.local.EngineRecordDao.activeByRecordType]
     * this table itself IS the source of truth being uploaded. */
    @Query("SELECT * FROM drives")
    suspend fun getAll(): List<Drive>

    /**
     * Looks a drive up by its portable [Drive.syncId] rather than the local autoincrement [Drive.id] -
     * [com.kevin.legion.backend.FleetReconcile] uses this to decide whether a server-refreshed row
     * is already present locally before inserting it, mirroring [EventReplicaDao.getByServerId]'s
     * role for a table that (unlike [com.kevin.legion.data.local.EventReplica]) has no unique index
     * of its own to upsert against - a drive, once finalised, never changes (this file's own class
     * doc), so "already present" is reason enough to skip re-inserting rather than to update.
     */
    @Query("SELECT * FROM drives WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Drive?

    /** By the local autoincrement id - [com.kevin.legion.vehicle.FleetEngineStore.syncDriveToServer]
     * reads the just-[insert]ed row fresh before pushing it, same shape as
     * [com.kevin.legion.data.local.ServiceRecordDao.getById]. */
    @Query("SELECT * FROM drives WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Drive?

    /** Records the server's uuid after a first successful push - see [Drive.serverId]'s own doc
     * comment for why this is bookkeeping only, never consulted for identity. */
    @Query("UPDATE drives SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)
}
