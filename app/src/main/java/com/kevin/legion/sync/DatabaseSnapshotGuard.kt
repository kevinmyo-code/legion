package com.kevin.legion.sync

/**
 * Pure refusal-guard logic for [DatabaseSnapshot]'s export path (Phase 0 of the
 * sync overhaul, Kevin 2026-08-12 - "an older APK dropped all 42 tables via
 * the destructive-downgrade fallback; refuse to upload a snapshot whose row
 * count looks like that same wipe"). Kept Android- and Drive-free, same shape
 * as [SyncMerge]'s pure planner, so the threshold itself is a plain JVM unit
 * test target with no Robolectric.
 *
 * **Threshold, argued.** A genuine day-to-day database shrinks by ones or
 * tens of rows at most - a deleted list item, a superseded provisional
 * ledger row (CLAUDE.md §4 rule 7's supersession), a purged old OBD-sample
 * month. A wipe - the actual 2026-08-12 incident - drops EVERY row in roughly
 * 30 of 42 tables at once, so the total collapses by an order of magnitude,
 * not a percentage point. "Fewer than half the previous total, or zero when
 * the previous total was nonzero" catches that collapse with wide margin
 * while still tolerating an ordinary week of deletions: even a driver
 * bulk-clearing every completed list item in one sitting would not halve the
 * WHOLE-DATABASE row count, because `obd_samples`/ledger/pantry/workouts/
 * meals/sleep all keep accumulating in the background regardless of what
 * happens in any one table. A tighter threshold (e.g. 90%) would false-positive
 * on real retention purges (`TelemetryRecorder`'s 365-day OBD-sample window);
 * a looser one (e.g. 10%) would not have caught tonight's incident, which
 * went to exactly zero.
 */
object DatabaseSnapshotGuard {
    /**
     * True if [newRowCount] must be refused against [previousRowCount] - the
     * newest already-uploaded generation's own recorded count. [previousRowCount]
     * null (or non-positive, which should not happen but is treated the same
     * as "nothing to compare against") means this is the first backup ever
     * taken for this Drive account, so nothing is refused: there is no prior
     * good copy this could be silently destroying.
     */
    fun shouldRefuse(newRowCount: Long, previousRowCount: Long?): Boolean {
        if (previousRowCount == null || previousRowCount <= 0) return false
        if (newRowCount == 0L) return true
        return newRowCount < previousRowCount / 2
    }

    /** Plain-language reason, surfaced to the driver and logged - never silent (see the
     * task brief's "on refusal, skip the upload and surface it"). */
    fun refusalReason(newRowCount: Long, previousRowCount: Long): String =
        "New backup has $newRowCount row(s) vs the last good backup's $previousRowCount - " +
            "that looks like data loss, not a real change. Skipped the upload; your last " +
            "good backup on Drive is untouched."
}
