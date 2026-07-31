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

    /** Total logged maintenance spend (ignores null costs) - feeds the build-sheet grand total. */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM service_records WHERE vehicleId = :vehicleId")
    suspend fun totalCost(vehicleId: String): Double

    /** Count of services logged in a time range - feeds MonthlyRecapController's aggregation. */
    @Query(
        "SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId " +
            "AND date >= :fromMs AND date <= :toMs"
    )
    suspend fun countInRange(vehicleId: String, fromMs: Long, toMs: Long): Int
}
