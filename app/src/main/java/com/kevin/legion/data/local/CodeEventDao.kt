package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodeEventDao {
    /** Returns the inserted row's local id (widened from `Unit`, backend-erp ticket 26 step 4) -
     * [com.kevin.legion.vehicle.FleetEngineStore.recordCodeEvent] needs it to push the row it just
     * wrote without a second read, same shape as [com.kevin.legion.data.local.DriveDao.insert].
     * Every existing caller already ignored the prior `Unit` return, so this widening breaks
     * nothing. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CodeEvent): Long

    @Query("SELECT * FROM code_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    suspend fun getAll(vehicleId: String): List<CodeEvent>

    @Query("SELECT * FROM code_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(vehicleId: String): CodeEvent?

    /** All events in a time range, ordered oldest-first. */
    @Query(
        "SELECT * FROM code_events WHERE vehicleId = :vehicleId " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC"
    )
    suspend fun getInRange(vehicleId: String, fromMs: Long, toMs: Long): List<CodeEvent>

    /** Count of code events in a time range - feeds MonthlyRecapController's aggregation. */
    @Query(
        "SELECT COUNT(*) FROM code_events WHERE vehicleId = :vehicleId " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs"
    )
    suspend fun countInRange(vehicleId: String, fromMs: Long, toMs: Long): Int

    /** Every code event on file, across all vehicles - what [com.kevin.legion.backend.FleetReconcile]
     * reads as the upload source, mirroring [DriveDao.getAll]'s "this table itself is the source of
     * truth" role for a table with no engine-record counterpart. */
    @Query("SELECT * FROM code_events")
    suspend fun getAllForUpload(): List<CodeEvent>

    /** Looks a code event up by its portable [CodeEvent.syncId] - the insert-if-absent replica check
     * [com.kevin.legion.backend.FleetReconcile] uses, same role [DriveDao.getBySyncId] plays for
     * [Drive]. A code event never changes once written (this DAO's own `insert` is the only writer
     * and there is no update path), so "already present" is reason enough to skip re-inserting. */
    @Query("SELECT * FROM code_events WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): CodeEvent?

    /** By the local autoincrement id - [com.kevin.legion.vehicle.FleetEngineStore.syncCodeEventToServer]
     * reads the just-[insert]ed row fresh before pushing it, same shape as
     * [com.kevin.legion.data.local.DriveDao.getById]. */
    @Query("SELECT * FROM code_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CodeEvent?

    /** Records the server's uuid after a first successful push - see [CodeEvent.serverId]'s own
     * doc comment for why this is bookkeeping only, never consulted for identity. */
    @Query("UPDATE code_events SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)

    /** `FleetSync`'s pull-side tombstone handling - same "no local `deleted` column, so a server
     * tombstone means a real local delete" reasoning as [DriveDao.deleteBySyncId]. */
    @Query("DELETE FROM code_events WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)
}
