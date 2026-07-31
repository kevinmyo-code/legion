package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [BuildEntry] - the build sheet / spend ledger.
 */
@Dao
interface BuildEntryDao {
    @Insert
    suspend fun insert(entry: BuildEntry): Long

    // Dormant: no caller wires a user-facing delete here yet. build_entries is a
    // UNION (append-only) sync table (B19 scope note) - a hard DELETE would be
    // invisible to the sync SELECT and get resurrected on the next pull, same
    // bug as B19. Wiring a delete flow here needs its own tombstone design
    // first, not a copy of the LWW car_tasks/places fix.
    @Delete
    suspend fun delete(entry: BuildEntry)

    /** Attaches (or clears) a photo path on an entry. */
    @Query("UPDATE build_entries SET photoPath = :path WHERE id = :id")
    suspend fun setPhoto(id: Long, path: String)

    /** Full build history for a vehicle, newest first. */
    @Query("SELECT * FROM build_entries WHERE vehicleId = :vehicleId ORDER BY date DESC")
    suspend fun getForVehicle(vehicleId: String): List<BuildEntry>

    /** History of one category (e.g. "mod"), newest first. */
    @Query("SELECT * FROM build_entries WHERE vehicleId = :vehicleId AND type = :type ORDER BY date DESC")
    suspend fun getForVehicleByType(vehicleId: String, type: String): List<BuildEntry>

    /** Total logged spend across build entries (ignores null costs). */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM build_entries WHERE vehicleId = :vehicleId")
    suspend fun totalSpend(vehicleId: String): Double

    /** Logged spend for one category. */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM build_entries WHERE vehicleId = :vehicleId AND type = :type")
    suspend fun spendByType(vehicleId: String, type: String): Double
}
