package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One OBD-II telemetry sample, captured every 30 s while driving.
 * The raw PID string (e.g. "0104" = engine load) is stored alongside
 * its decoded value so the schema stays generic across all PIDs.
 * GPS fields are nullable — they fill in only when location is live.
 *
 * Estimated storage: 18 MB/year at 30-second intervals across a normal
 * driving routine. This is the moat table: a compounding time series that
 * no competitor has because no competitor is embedded in the car.
 *
 * v25: `(vehicleId, pid, timestamp)` composite index (Kevin's device, 2026-08-16). At 18,694 rows
 * and ZERO indexes, [OdbSampleDao.getRange] and its siblings were `SCAN obd_samples` plus a temp
 * b-tree sort on every call - the FAULTS drilldown calls [OdbSampleDao.getRange] twice per code
 * event (speed + rpm, `ui/fleet/FleetDrilldowns.kt`), so 45 code events meant 90 full scans to draw
 * one screen. The column order matches every hot query's shape exactly: `WHERE vehicleId=? AND
 * pid=? AND timestamp BETWEEN ? AND ? ORDER BY timestamp` - [OdbSampleDao.getRange],
 * [OdbSampleDao.getRangeNewestFirst], [OdbSampleDao.getLatest], and [OdbSampleDao.summarize] all
 * match this prefix and turn into an index `SEARCH`, no temp sort. [OdbSampleDao.recordedPids] and
 * [OdbSampleDao.totalCount] filter on `vehicleId` alone, so they get a narrower covering index scan
 * instead of a full table scan even though they don't use the trailing columns.
 * [OdbSampleDao.lastSampleMs]/[OdbSampleDao.firstSampleMs] (`MAX`/`MIN(timestamp)` per vehicle) and
 * [OdbSampleDao.recentTimestamps] (global timestamp order across all of one vehicle's PIDs) are
 * NOT fully served by this index - the leading `pid` column breaks a single sorted-by-timestamp
 * run across PIDs - but a second index was judged not worth it: none of those three sit on a
 * per-row-visible hot path (they run once per screen load, or once per service start for the
 * `purgeOlderThan` maintenance sweep), and doubling the index count on the app's single largest
 * table doubles the per-insert write cost for no measured win. See §7's write-cost note below.
 *
 * Write cost: [com.kevin.legion.vehicle.TelemetryRecorder] inserts ~9 rows per 30 s tick while
 * driving (one per polled PID). One additional B-tree insert per row, on an index no wider than
 * three columns, against SQLite's WAL-mode buffered writes - not material at this rate. It would
 * matter on a table taking thousands of writes per second; this one takes about one every 3-4
 * seconds while the car is running.
 */
@Entity(
    tableName = "obd_samples",
    indices = [Index(value = ["vehicleId", "pid", "timestamp"])],
)
data class OdbSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,      // Vehicle.obdMac
    val pid: String,            // raw PID code, e.g. "0104"
    val value: Double,          // decoded numeric value in SI/imperial unit
    val unit: String,           // human label, e.g. "rpm", "°F", "V"
    val timestamp: Long,        // System.currentTimeMillis()
    val lat: Double? = null,
    val lng: Double? = null,
)
