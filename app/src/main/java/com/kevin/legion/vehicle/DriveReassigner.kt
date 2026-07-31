package com.kevin.legion.vehicle

import com.kevin.legion.data.local.DriveReassignment

/**
 * Applies "this drive belongs to a different car" corrections (car manager,
 * 2026-07-16).
 *
 * Split into a pure [plan] and a DB-touching applier (in `SyncEngine`/the DAO
 * caller) for the same reason [com.kevin.legion.sync.SyncMerge] is: the part
 * with the sharp edges - ordering, chaining, self-cancelling rules - unit-tests
 * without a device or a live Drive.
 *
 * **Why rules and not a one-off UPDATE.** See [DriveReassignment]'s doc: a direct
 * re-key clones a drive onto both cars instead of moving it, because `obd_samples`
 * syncs UNION on an identity that INCLUDES vehicleId. The rules are re-applied on
 * every sync pass - crucially, AFTER the `obd_samples` merge as well as before, so
 * rows UNION has just resurrected from Drive get re-keyed again before the
 * converged snapshot uploads.
 */
object DriveReassigner {

    /** One `UPDATE obd_samples SET vehicleId = [newVehicleId] WHERE ...` worth of work. */
    data class Move(
        val fromVehicleId: String,
        val toVehicleId: String,
        val fromMs: Long,
        val toMs: Long,
    )

    /**
     * Turns stored rules into the moves to execute, in order.
     *
     * Ordered by [DriveReassignment.updatedAt] so a later correction of the same
     * drive lands last and wins - and so CHAINS resolve correctly: move A->B at
     * t=1 then B->C at t=2 leaves the rows on C, because the second rule's source
     * is what the first rule produced. Applying them out of order would strand the
     * rows on B.
     *
     * Rules whose source and target are the same car are dropped: they are no-ops,
     * and a self-move would be an infinite no-op on every sync pass forever.
     */
    fun plan(rules: List<DriveReassignment>): List<Move> =
        rules
            .sortedBy { it.updatedAt }
            .filter { it.vehicleId != it.newVehicleId }
            .map { Move(it.vehicleId, it.newVehicleId, it.fromMs, it.toMs) }
}
