package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OilAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: OilAnalysis): Long

    @Query("SELECT * FROM oil_analyses WHERE vehicleId = :vehicleId ORDER BY date DESC")
    suspend fun getAll(vehicleId: String): List<OilAnalysis>

    @Query("SELECT * FROM oil_analyses WHERE vehicleId = :vehicleId ORDER BY date DESC LIMIT 1")
    suspend fun getLatest(vehicleId: String): OilAnalysis?

    // Dormant: no caller wires a user-facing delete here yet. oil_analyses is a
    // UNION (append-only) sync table (B19 scope note) - a hard DELETE would be
    // invisible to the sync SELECT and get resurrected on the next pull, same
    // bug as B19. Wiring a delete flow here needs its own tombstone design
    // first, not a copy of the LWW car_tasks/places fix.
    @Query("DELETE FROM oil_analyses WHERE id = :id")
    suspend fun delete(id: Long)

    /** Every oil analysis on file, across all vehicles - the upload source for
     * [com.kevin.legion.backend.FleetReconcile], same role as [CodeEventDao.getAllForUpload]. */
    @Query("SELECT * FROM oil_analyses")
    suspend fun getAllForUpload(): List<OilAnalysis>

    /** Looks an oil analysis up by its portable [OilAnalysis.syncId] - the insert-if-absent replica
     * check [com.kevin.legion.backend.FleetReconcile] uses, same role as [CodeEventDao.getBySyncId]. */
    @Query("SELECT * FROM oil_analyses WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): OilAnalysis?
}
