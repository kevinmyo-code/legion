package com.kevin.midnightai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One finished drive, written by `TelemetryRecorder.finalizeDrive` (v13, 2026-10-17) so the fleet
 * upload can send the engine's `drives` table a drive whose start was RECORDED, not inferred.
 *
 * - [startedAt] is the moment the recorder saw the engine come on for this drive (the tick where
 *   `engineWasOn` flipped true), not a guess from sample gaps.
 * - [endedAt] is when `finalizeDrive` ran (two engine-off ticks after the last running one).
 * - [gallons] is null, never 0.0 and never derived, unless the drive's own MPG figure exists
 *   (miles and MAF-integrated gallons both past their minimums, the same gate that writes the
 *   `MPG_TRIP` marker). Unknown fuel and no fuel are different facts.
 * - [endReason] is always `ENGINE_OFF` on the head unit: a drive only ends here when RPM stays at
 *   zero. The head unit has no link-lost finalize path (the phone's `LINK_LOST` has no analogue
 *   here), so `ENGINE_OFF` is the only value it genuinely knows. The server accepts free text.
 * - [syncId] is the UUID minted at insert time and is the engine's identity for the row, so a
 *   retried upload is a harmless re-PUT.
 *
 * **No backfill.** The table starts empty at the v12 -> v13 upgrade; drives recorded before it
 * exist only as `TRIP_MILES`/`MPG_TRIP` marker samples and are deliberately not reconstructed
 * (that would need an inferred start time).
 */
@Entity(tableName = "drives")
data class Drive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val startedAt: Long,
    val endedAt: Long,
    val miles: Double,
    val gallons: Double? = null,
    val endReason: String = END_REASON_ENGINE_OFF,
    val syncId: String = java.util.UUID.randomUUID().toString(),
) {
    companion object {
        const val END_REASON_ENGINE_OFF = "ENGINE_OFF"
    }
}
