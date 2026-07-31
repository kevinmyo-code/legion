package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OdbSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: OdbSample)

    /** All samples for a PID in a time range, ordered oldest-first. */
    @Query(
        "SELECT * FROM obd_samples " +
            "WHERE vehicleId = :vehicleId AND pid = :pid " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getRange(vehicleId: String, pid: String, fromMs: Long, toMs: Long): List<OdbSample>

    /** Latest N samples for a PID — used for quick recent-trend reads. */
    @Query(
        "SELECT * FROM obd_samples " +
            "WHERE vehicleId = :vehicleId AND pid = :pid " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getLatest(vehicleId: String, pid: String, limit: Int): List<OdbSample>

    /** Purge samples older than a cutoff to cap storage growth. */
    @Query("DELETE FROM obd_samples WHERE timestamp < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)

    /** Which PIDs actually have data for this car. Varies per car: TelemetryRecorder only writes PIDs the ECU answers. */
    @Query("SELECT DISTINCT pid FROM obd_samples WHERE vehicleId = :vehicleId")
    suspend fun recordedPids(vehicleId: String): List<String>

    /** Newest sample time for a car, or null if it has none. Powers the CARS roster's "last driven". */
    @Query("SELECT MAX(timestamp) FROM obd_samples WHERE vehicleId = :vehicleId")
    suspend fun lastSampleMs(vehicleId: String): Long?

    /** Total sample count, for the history header. */
    @Query("SELECT COUNT(*) FROM obd_samples WHERE vehicleId = :vehicleId")
    suspend fun totalCount(vehicleId: String): Int

    /** Oldest sample timestamp, for the history header's "since" date. */
    @Query("SELECT MIN(timestamp) FROM obd_samples WHERE vehicleId = :vehicleId")
    suspend fun firstSampleMs(vehicleId: String): Long?

    /** Timestamps only, newest-first, for splitting drives by gap without loading values. */
    @Query("SELECT timestamp FROM obd_samples WHERE vehicleId = :vehicleId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentTimestamps(vehicleId: String, limit: Int): List<Long>

    /** Min/max/avg/count/span for one PID within a window, for the per-drive/per-range summary cards. */
    @Query(
        "SELECT MIN(value) AS min, MAX(value) AS max, AVG(value) AS avg, COUNT(*) AS count, " +
            "MIN(timestamp) AS firstMs, MAX(timestamp) AS lastMs FROM obd_samples " +
            "WHERE vehicleId=:vehicleId AND pid=:pid AND timestamp>=:fromMs AND timestamp<=:toMs"
    )
    suspend fun summarize(vehicleId: String, pid: String, fromMs: Long, toMs: Long): PidSummary?
}
