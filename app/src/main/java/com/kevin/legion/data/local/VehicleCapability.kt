package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One Mode-01 PID that one vehicle answers - the persisted capability profile
 * (`vehicle/PidSpec.kt`, 2026-08-12).
 *
 * **This is how LEGION scales to cars it has never seen.** Nothing here is per-model and nothing is
 * hand-maintained: every OBD-II vehicle publishes its own support bitmask, so plugging into a new
 * car and writing down what it answered is the entire onboarding process. A 2017 F-150 and a 1998
 * XJ produce different row sets from identical code.
 *
 * Persisted rather than kept in memory on [com.kevin.legion.vehicle.ObdBluetoothManager] for three
 * reasons, all of which are questions the driver actually asks:
 * - "Does the truck report oil temperature?" is answerable **while parked indoors**, with no dongle.
 * - Comparing cars ("which of mine can log boost?") is a query, not a reconnect-to-each exercise.
 * - The capability set is evidence about a specific car, so it belongs with the car, not with the
 *   transient Bluetooth session that happened to discover it.
 *
 * Composite primary key of (vehicleId, pid): one row per supported PID per car, rather than a
 * comma-separated blob on [Vehicle]. A set encoded into a TEXT column cannot be queried
 * ("which vehicles support 0x5C") without string matching, and this schema's posture is that a
 * thing worth storing is worth storing as rows.
 *
 * [detectedAt] is the last scan that confirmed it. A PID silently vanishing between scans would be
 * interesting (a module dropped off the bus), so rows are never deleted by a later scan that fails
 * to see them - see [VehicleCapabilityDao.replaceForVehicle] for the one place that does clear them.
 */
@Entity(tableName = "vehicle_capabilities", primaryKeys = ["vehicleId", "pid"])
data class VehicleCapability(
    /** [Vehicle.obdMac], matching every other per-vehicle table in this schema. */
    val vehicleId: String,
    /** Mode-01 PID number, e.g. 0x5C for engine oil temperature. */
    val pid: Int,
    val detectedAt: Long = System.currentTimeMillis(),
)

@Dao
interface VehicleCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<VehicleCapability>)

    @Query("SELECT pid FROM vehicle_capabilities WHERE vehicleId = :vehicleId ORDER BY pid ASC")
    suspend fun pidsForVehicle(vehicleId: String): List<Int>

    @Query("SELECT * FROM vehicle_capabilities WHERE vehicleId = :vehicleId ORDER BY pid ASC")
    suspend fun forVehicle(vehicleId: String): List<VehicleCapability>

    /** Every vehicle known to answer [pid] - "which of my cars can log boost?" */
    @Query("SELECT DISTINCT vehicleId FROM vehicle_capabilities WHERE pid = :pid")
    suspend fun vehiclesSupporting(pid: Int): List<String>

    @Query("DELETE FROM vehicle_capabilities WHERE vehicleId = :vehicleId")
    suspend fun clearForVehicle(vehicleId: String)

    @Query("SELECT MAX(detectedAt) FROM vehicle_capabilities WHERE vehicleId = :vehicleId")
    suspend fun lastDetectedAt(vehicleId: String): Long?

    /**
     * Replaces a vehicle's whole profile with a freshly scanned one.
     *
     * The only path that deletes rows, and it deletes them **only when the new scan actually found
     * something**. A scan that comes back empty is overwhelmingly a failed handshake rather than a
     * car that lost every sensor it had, and letting that wipe a good profile would mean one bad
     * connect erases what the app knows about the car - exactly the silent-data-loss shape this repo
     * keeps closing. An empty scan is therefore a no-op, and the previous profile stands.
     */
    @androidx.room.Transaction
    suspend fun replaceForVehicle(vehicleId: String, pids: Set<Int>, at: Long) {
        if (pids.isEmpty()) return
        clearForVehicle(vehicleId)
        insertAll(pids.map { VehicleCapability(vehicleId = vehicleId, pid = it, detectedAt = at) })
    }
}
