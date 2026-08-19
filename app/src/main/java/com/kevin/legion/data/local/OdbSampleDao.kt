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

    /**
     * Newest-first samples for a PID in a time range, capped at [limit].
     *
     * [getRange] is unbounded, which is correct for an agent summarising a
     * drive and wrong for a chart over "all time": `obd_samples` grows at
     * roughly a million rows a year per car (see [OdbSample]'s storage note),
     * and materialising that many entities to then throw all but 200 buckets
     * away would stall or OOM before it ever drew. The cap is on the QUERY, so
     * the rows never leave SQLite. Newest-first because a truncated window
     * should keep the RECENT end, not an arbitrary old prefix - the caller
     * re-sorts for display.
     *
     * The chart's own summary comes from [summarize], which aggregates in SQL
     * over the whole window, so min/max/avg stay exact even when this
     * truncates.
     */
    @Query(
        "SELECT * FROM obd_samples " +
            "WHERE vehicleId = :vehicleId AND pid = :pid " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRangeNewestFirst(
        vehicleId: String,
        pid: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<OdbSample>

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
