package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A real drive-boundary row - the object [com.kevin.legion.vehicle.TelemetryRecorder] never had
 * (`.scratch/drive-ui/issues/05-trip-content.md` Q14, `09-mpg-scale-bug.md`'s "bigger finding").
 * Before this, the only notion of "a drive" was `engineWasOn` as a private local in
 * [com.kevin.legion.vehicle.TelemetryRecorder.run] and day-granularity [DailyDriveLog] - nothing
 * recorded when a drive actually started or ended, or why it ended.
 *
 * **The defect this closes.** `run()`'s tick guard used to read
 * `if (!ObdBluetoothManager.isConnected || ConversationState.isBusy) continue` - a single `continue`
 * for BOTH a voice turn (correctly skip-only, no drive state touched) and a lost Bluetooth link
 * (which needs to actually END the drive). Because the engine-off finalizer sat below that guard, a
 * dropped link meant every subsequent tick `continue`d forever: `engineWasOn` stayed `true`,
 * `driveMiles`/`driveGallons` kept accumulating in memory, and the NEXT reconnect resumed the SAME
 * drive rather than starting a new one. Measured consequence in Kevin's real database: his one
 * finalised drive spans 610 minutes around a single 9-hour parked gap - two separate sessions merged
 * into one, with `MAX_DT_SEC` the only reason it wasn't far worse (it clamps a stale gap's
 * contribution to at most 90 seconds of distance/fuel, but does nothing to split the drive itself).
 *
 * **[endReason] names why the drive ended**, stored as plain TEXT with no CHECK constraint -
 * widening this list later needs no migration (CLAUDE.md §5's "widening a TEXT enum stored as TEXT
 * is not a migration"). Two values exist today:
 *  - `ENGINE_OFF` - `offTicks >= 2` (60s of rpm reading 0/null), the unambiguous case.
 *  - `LINK_LOST` - `LINK_LOST_TICKS` (2 minutes) of `!ObdBluetoothManager.isConnected` while a drive
 *    was in progress. Deliberately LONGER than engine-off's threshold: a brief Bluetooth blip must
 *    not split one real drive into two, whereas an engine genuinely being off is never ambiguous.
 *
 * **[gallons] is nullable and stays null, never `0.0`, when MAF never answered a usable reading** -
 * the same "don't lie about an unmeasured quantity" posture [MpgTrust] enforces for the mpg figure
 * itself. A `0.0` here would read as "burned no fuel", which is a different claim than "fuel burn
 * was never measured". [com.kevin.legion.vehicle.TelemetryRecorder.gallonsWorthRecording] is the
 * single gate deciding which of the two this row gets.
 *
 * **[miles] is NEVER gated behind [gallons]** - ticket 09's whole point: distance does not depend on
 * fuel math, so a drive with usable miles but a silent MAF still gets a real [miles] value, with
 * [gallons] null alongside it. This mirrors the TRIP_MILES/MPG_TRIP split already applied to the
 * `obd_samples` summary rows [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive] writes
 * alongside this one - this table is additive to those, not a replacement for them; other surfaces
 * (`MonthlyRecapController`, `DailyDriveLogController`) still read the `obd_samples` rows.
 *
 * **No UI reads this table yet** (ticket 05's own instruction: the driving screen's trip block stays
 * `NOT TRACKING` until this data has been seen on a real drive). It exists purely so a live trip
 * readout has something correct to eventually read.
 *
 * **Sync `Mode.UNION` on [syncId], no update, no delete** - append-only falsifiable facts about the
 * car, the same posture `obd_samples`/`code_events`/`code_clear_events` already carry (see
 * `sync/SyncEngine.kt`'s `REGISTRY`). A drive, once finalised, never changes.
 */
@Entity(tableName = "drives")
data class Drive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    /** Epoch ms the drive began - the moment `engineWasOn` flipped `true` for this drive. */
    val startedAt: Long,
    /** Epoch ms [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive] ran for this drive. */
    val endedAt: Long,
    val miles: Double,
    /** Null, never `0.0`, when MAF was silent for the whole drive - see the class doc. */
    val gallons: Double? = null,
    /** `ENGINE_OFF` or `LINK_LOST` today - plain TEXT, widening needs no migration. See class doc. */
    val endReason: String,
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
