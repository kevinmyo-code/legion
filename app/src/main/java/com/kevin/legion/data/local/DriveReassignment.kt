package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One "this drive belongs to a different car" correction (car manager, 2026-07-16).
 *
 * **Why this table exists instead of just re-keying the rows.** `obd_samples` syncs
 * as `Mode.UNION` with identity `(vehicleId, pid, timestamp)`. Change a row's
 * vehicleId and its sync identity changes: the originals are still on Drive under
 * the OLD id, UNION sees them as unseen locally, and **re-inserts them**. A direct
 * re-key doesn't move a drive, it CLONES it onto both cars, permanently, on every
 * device. Tombstones don't rescue it either - UNION never updates an existing row,
 * so a tombstone can't propagate (B19's tombstones work only because `car_tasks`
 * and `places` are LWW).
 *
 * So the correction is stored as a **rule**, not a mutation. It is tiny, it is LWW,
 * and [com.kevin.legion.vehicle.DriveReassigner] applies it to local rows on
 * every sync pass - including AFTER the `obd_samples` merge, so rows UNION has just
 * resurrected get re-keyed again before the converged snapshot is uploaded.
 *
 * The cost lands where the frequency is: corrections are rare and ~100 bytes,
 * telemetry is constant and ~18MB/yr, so the rare thing carries the storage and the
 * moat table needs no migration.
 *
 * A time RANGE, not a list of row ids, on purpose: if another device later syncs
 * more samples into the same window, the rule still catches them.
 */
@Entity(tableName = "drive_reassignments")
data class DriveReassignment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Portable cross-device identity (the local autoincrement id never leaves this device). */
    @ColumnInfo(defaultValue = "''") val syncId: String = "",
    /** The car the samples are currently attributed to. */
    val vehicleId: String,
    /** Drive window start, inclusive (millis). */
    val fromMs: Long,
    /** Drive window end, inclusive (millis). */
    val toMs: Long,
    /** The car they should be attributed to. */
    val newVehicleId: String,
    /** LWW clock, and the order rules are applied in so a re-correction wins. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
