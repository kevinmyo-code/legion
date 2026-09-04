package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodeClearEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CodeClearEvent): Long

    /** Newest first - the UI (D7's union rule) and any future history surface both want this order. */
    @Query("SELECT * FROM code_clear_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    suspend fun getAll(vehicleId: String): List<CodeClearEvent>

    /**
     * The single anchor D7's union rule filters against - the most recent row whose `outcome` is
     * `CLEARED` specifically. **Never RETURNED or UNVERIFIED** - D7's own text: "RETURNED and
     * UNVERIFIED clears do NOT filter anything." Only a genuine full clear ever moves the anchor
     * or earns the STORED CODES block's `CLEARED <date>` line.
     */
    @Query(
        "SELECT * FROM code_clear_events WHERE vehicleId = :vehicleId AND outcome = 'CLEARED' " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestCleared(vehicleId: String): CodeClearEvent?

    /** Every code-clear event on file, across all vehicles - the upload source for
     * [com.kevin.legion.backend.FleetReconcile], same role as [CodeEventDao.getAllForUpload]. */
    @Query("SELECT * FROM code_clear_events")
    suspend fun getAllForUpload(): List<CodeClearEvent>

    /** Looks a code-clear event up by its portable [CodeClearEvent.syncId] - the insert-if-absent
     * replica check [com.kevin.legion.backend.FleetReconcile] uses, same role as
     * [CodeEventDao.getBySyncId]. */
    @Query("SELECT * FROM code_clear_events WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): CodeClearEvent?

    /** By the local autoincrement id - [com.kevin.legion.vehicle.FleetEngineStore.syncCodeClearEventToServer]
     * reads the just-[insert]ed row fresh before pushing it, same shape as
     * [com.kevin.legion.data.local.DriveDao.getById]. */
    @Query("SELECT * FROM code_clear_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CodeClearEvent?

    /** Records the server's uuid after a first successful push - see [CodeClearEvent.serverId]'s
     * own doc comment for why this is bookkeeping only, never consulted for identity. */
    @Query("UPDATE code_clear_events SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)

    /** `FleetSync`'s pull-side tombstone handling - same "no local `deleted` column, so a server
     * tombstone means a real local delete" reasoning as [DriveDao.deleteBySyncId]. */
    @Query("DELETE FROM code_clear_events WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)
}
