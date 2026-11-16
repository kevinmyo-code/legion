package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One module scan of one car (brand-neutral diagnostics, 2026-11-15). Phone only: the engine side
 * is a follow-up, so this table is not synced.
 *
 * [vehicleId] is the OBD recording id at scan time ([com.kevin.legion.vehicle.ActiveVehicle.recordingId]):
 * the car the VIN said the dongle is in, or a `pending:` id while that is unknown. Kevin's answer to
 * "which car is this" moves held scans with the rest of the connection's OBD data
 * ([com.kevin.legion.vehicle.FleetEngineStore.reattributeObdData]).
 *
 * [reportJson] is the whole [com.kevin.legion.vehicle.diag.ScanReport], raw adapter replies
 * included beside every decoded code (CLAUDE.md section 4 rule 8: keep the evidence). The other
 * columns are a summary for lists; the report is the record.
 */
@Entity(tableName = "diag_scans", indices = [Index(value = ["vehicleId", "startedAtMs"])])
data class DiagScan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    /** Bus name ([com.kevin.legion.vehicle.diag.BusKind]). */
    val bus: String,
    /** Modules that answered (0 on a pre-CAN car). */
    val moduleCount: Int,
    /** Distinct fault codes across the engine and every module. */
    val faultCount: Int,
    /** False when the scan stopped early, ran out of time, or was refused. */
    val complete: Boolean,
    val reportJson: String,
)

@Dao
interface DiagScanDao {
    @Insert
    suspend fun insert(scan: DiagScan): Long

    @Query("SELECT * FROM diag_scans WHERE vehicleId = :vehicleId ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun latest(vehicleId: String, limit: Int): List<DiagScan>

    @Query("SELECT * FROM diag_scans WHERE id = :id")
    suspend fun get(id: Long): DiagScan?

    @Query("UPDATE diag_scans SET vehicleId = :toId WHERE vehicleId = :fromId")
    suspend fun rekeyAll(fromId: String, toId: String): Int

    @Query(
        "UPDATE diag_scans SET vehicleId = :toId WHERE vehicleId = :fromId " +
            "AND startedAtMs >= :fromMs AND startedAtMs <= :toMs",
    )
    suspend fun rekeyRange(fromId: String, toId: String, fromMs: Long, toMs: Long): Int
}
