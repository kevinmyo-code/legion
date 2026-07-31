package com.kevin.legion.vehicle

/**
 * Pure Kotlin history-shaping logic for the HISTORY tab of [com.kevin.legion.ui.ObdSheet].
 * No Android imports, no Room - kept separate from the DAO/Compose layers so it can be reasoned
 * about and unit-tested on the plain JVM, same pattern as SyncMerge/SyncCodec.
 */
object ObdHistory {

    /** A contiguous run of samples: one drive. */
    data class DriveWindow(val fromMs: Long, val toMs: Long)

    /**
     * A drive boundary: any gap between two consecutive samples bigger than this is treated as
     * "engine off, then on again later", not "still driving". TelemetryRecorder samples every 30s
     * while the engine is on, so a 10-minute gap is unambiguous - the driver parked.
     */
    const val DRIVE_GAP_MS = 10 * 60 * 1000L

    /**
     * Splits sample timestamps into drives by TIME GAP, not by the TRIP_MILES marker.
     * TelemetryRecorder.finalizeDrive early-returns (writes no marker) for drives under 1 mile or
     * 0.05 gal, so a marker-based split would silently swallow short drives into whichever drive
     * came next. A gap larger than [DRIVE_GAP_MS] is the only signal that survives every drive length.
     *
     * PRECONDITION: [timestampsDescending] must already be sorted newest-first (as
     * [com.kevin.legion.data.local.OdbSampleDao.recentTimestamps] returns it). Unsorted input
     * is not supported and produces undefined windows.
     *
     * @return drive windows, newest first. Empty input returns an empty list. A single timestamp
     *   returns one window with fromMs == toMs.
     */
    fun splitDrives(timestampsDescending: List<Long>): List<DriveWindow> {
        if (timestampsDescending.isEmpty()) return emptyList()
        val windows = mutableListOf<DriveWindow>()
        var windowEnd = timestampsDescending[0] // newest sample in the window currently open
        var windowStart = timestampsDescending[0]
        for (i in 1 until timestampsDescending.size) {
            val previous = timestampsDescending[i - 1] // newer
            val current = timestampsDescending[i]      // older
            if (previous - current > DRIVE_GAP_MS) {
                windows.add(DriveWindow(fromMs = windowStart, toMs = windowEnd))
                windowEnd = current
                windowStart = current
            } else {
                windowStart = current
            }
        }
        windows.add(DriveWindow(fromMs = windowStart, toMs = windowEnd))
        return windows
    }

    /**
     * Buckets [points] (timestamp-ms to value, any order) into at most [maxBuckets] time buckets,
     * each averaged, so a chart never has to draw more than [maxBuckets] points. A year of 30s
     * samples is roughly a million rows - plotting them all would stutter or OOM a cheap head
     * unit's Canvas. Returns points ordered oldest-first. No-op (returns as-is, sorted) if
     * [points] already fits within [maxBuckets].
     */
    fun downsample(points: List<Pair<Long, Double>>, maxBuckets: Int = 200): List<Pair<Long, Double>> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.first }
        if (sorted.size <= maxBuckets) return sorted
        val fromMs = sorted.first().first
        val toMs = sorted.last().first
        val span = (toMs - fromMs).coerceAtLeast(1)
        val bucketWidth = (span / maxBuckets).coerceAtLeast(1)
        val buckets = LinkedHashMap<Long, MutableList<Double>>()
        for ((ts, value) in sorted) {
            val bucket = fromMs + ((ts - fromMs) / bucketWidth) * bucketWidth
            buckets.getOrPut(bucket) { mutableListOf() }.add(value)
        }
        return buckets.map { (bucketTs, values) -> bucketTs to values.average() }
    }

    /** Plain-word label for a recorded PID/marker code. Total: unknown codes return the raw string, never crash. */
    fun pidLabel(pid: String): String = when (pid) {
        "010C" -> "RPM"
        "0105" -> "Coolant"
        "0104" -> "Engine load"
        "0110" -> "Airflow (MAF)"
        "010D" -> "Speed"
        "012F" -> "Fuel level"
        "0106" -> "Short fuel trim"
        "0107" -> "Long fuel trim"
        "ATRV" -> "Battery voltage"
        "010F" -> "Intake air"
        "MPG_TRIP" -> "MPG"
        "TRIP_MILES" -> "Trip distance"
        "COLD_START" -> "Cold start"
        else -> pid
    }
}
