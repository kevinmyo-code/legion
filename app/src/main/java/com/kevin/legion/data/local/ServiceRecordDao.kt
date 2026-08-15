package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ServiceRecord].
 */
@Dao
interface ServiceRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ServiceRecord)

    @Query("SELECT * FROM service_records ORDER BY date DESC")
    fun getAllRecords(): Flow<List<ServiceRecord>>

    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId ORDER BY date DESC")
    fun getRecordsForVehicle(vehicleId: String): Flow<List<ServiceRecord>>

    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId ORDER BY date DESC LIMIT 1")
    suspend fun getMostRecentForVehicle(vehicleId: String): ServiceRecord?

    /** One-shot recent history for the maintenance worker (no Flow to collect). */
    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentForVehicle(vehicleId: String, limit: Int): List<ServiceRecord>

    /**
     * Total logged maintenance spend, in cents (ignores null costs) - feeds the
     * build-sheet grand total. Cents, never dollars (CLAUDE.md §4 rule 3) - callers
     * that combine this with a dollar figure (e.g. [BuildEntry.cost]) must divide by
     * 100 themselves; this DAO stays in the same unit as the column it sums.
     */
    @Query("SELECT COALESCE(SUM(costCents), 0) FROM service_records WHERE vehicleId = :vehicleId")
    suspend fun totalCost(vehicleId: String): Long

    /** Count of services logged in a time range - feeds MonthlyRecapController's aggregation. */
    @Query(
        "SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId " +
            "AND date >= :fromMs AND date <= :toMs"
    )
    suspend fun countInRange(vehicleId: String, fromMs: Long, toMs: Long): Int

    /** Total records for a vehicle - ticket 09's FLEET "NOT BUILT YET" block needs a real count, not a hardcoded one. */
    @Query("SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId")
    suspend fun countForVehicle(vehicleId: String): Int

    /**
     * True if a precise, actually-logged service exists at or after [atOrAfterMs] - ticket 08's
     * backfill-conflict rule (`.scratch/fleet-maintenance/issues/08-matching-a-logged-service-to-an-item.md`).
     * Real damage this closes: on Kevin's device a `log_service` wrote a record AND its anchor at
     * 118,374; fourteen seconds later a `log_past_service` backfill silently overwrote that anchor
     * to 118,483 and nulled its date - a remembered approximation beat a precise fact. [serviceName]
     * must be the SAME name the anchor write would use (the caller's already-matched/canonicalised
     * name, never the driver's raw phrasing) or this cannot see the record it exists to protect.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM service_records WHERE vehicleId = :vehicleId " +
            "AND serviceName = :serviceName AND date >= :atOrAfterMs)"
    )
    suspend fun hasRecordAtOrAfter(vehicleId: String, serviceName: String, atOrAfterMs: Long): Boolean
}
