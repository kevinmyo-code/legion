package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodeEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CodeEvent)

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
}
