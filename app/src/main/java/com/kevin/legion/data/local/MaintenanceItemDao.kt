package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [MaintenanceItem].
 *
 * **Targeted writes (ticket 05, `.scratch/fleet-maintenance/issues/05-an-edit-that-actually-sticks.md`),
 * mirroring [VehicleDao]'s own fix (ticket 13).** [upsertStamped]/[upsert] REPLACE the whole row -
 * the exact shape that let a read-modify-write in [com.kevin.legion.vehicle.VehicleController
 * .logServiceDirect] silently lose a concurrent interval edit. [setIntervals]/[setAnchor]/
 * [setNeverDone]/[softDelete] below touch only the columns their own doc names, and every one
 * **returns the affected row count** - ticket 05's no-op guard, adopted as law for this whole map:
 * a targeted write against a `(vehicleId, serviceName)` pair that does not exist succeeds at the
 * SQL level while writing nothing, and the caller MUST check the count and surface a zero in
 * words rather than reporting success on a write that changed nothing (the exact "I changed the
 * oil interval to 7,500" bug the ticket is named for). [upsert]/[insertAll]/[insertIgnore] survive
 * for GENUINE INSERTS only - see each one's own doc. [insertIgnore] additionally NEVER REPLACEs a
 * conflicting row the way [upsert] does (ticket 14 review, BLOCKING 2) - use it, not [upsert], for
 * any accept-a-candidate write where the candidate may be stale by the time the driver taps accept.
 */
@Dao
interface MaintenanceItemDao {
    /**
     * REPLACES THE WHOLE ROW. Use ONLY to create a row (a new item, hand-added or seeded) - never
     * to edit an existing one, or a concurrent writer's edit to any column this caller does not
     * own is silently clobbered. Same warning [VehicleDao.upsertStamped] carries, for the same
     * reason: this is what let a driver-edited interval get lost under [logServiceDirect]'s old
     * read-modify-write shape.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStamped(item: MaintenanceItem)

    /** Stamps updatedAt for cross-device sync LWW (S1), then upserts (see VehicleDao.upsert). */
    suspend fun upsert(item: MaintenanceItem) =
        upsertStamped(item.copy(updatedAt = System.currentTimeMillis()))

    // Seed insert: fresh rows already carry a construction-time updatedAt, and
    // IGNORE means an existing (already-stamped) row is left untouched. This is
    // also, deliberately, what stops the factory seed from ever overwriting a
    // driver-edited interval (ticket 05 decision 1) and what stops a re-seed
    // from resurrecting a tombstoned item (ticket 07) - both are side effects
    // of IGNORE that used to be accidental protection and are now the intended
    // mechanism, kept exactly as-is on purpose.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MaintenanceItem>)

    /**
     * Single-row counterpart to [insertAll], added for ticket 14's populate WOULD ADD accept
     * (`ui/fleet/PopulateWrites.kt.writePopulateAdd`, ticket 14 review BLOCKING 2,
     * `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`). [upsert] REPLACEs
     * the whole row - this DAO's own doc already warns that is for creating a row only, and
     * `writePopulateAdd` was doing exactly the edit it warns against: a `wouldAdd` candidate is
     * computed at diff-load time and can sit un-accepted while the driver reviews, so a row with the
     * SAME `(vehicleId, serviceName)` can appear in that window (a sync merge landing mid-review, a
     * voice `log_service` orphan, or the near-miss/keyword gap BLOCKING 1b closes) - tapping ADD on a
     * stale candidate must never clobber whatever that concurrent write left, including its anchor or
     * `CONFIRMED` provenance. `IGNORE` here is the DAO's normal "genuine insert only" contract, not a
     * new one.
     *
     * Room's single-item `@Insert` return is the SQLite rowid on a real insert, or **-1 on an
     * IGNOREd conflict** - the caller checks for `-1L` exactly the way every other targeted write in
     * this file checks an affected-row count of zero (ticket 05's law), and surfaces it as
     * "already on file - check WOULD CHANGE" rather than reporting a silent overwrite as success.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: MaintenanceItem): Long

    /**
     * Edits ONLY [MaintenanceItem.intervalMiles]/[MaintenanceItem.intervalMonths]/
     * [MaintenanceItem.intervalSource] - never `lastDoneMileage`/`lastDoneDate`/`neverDone`, which
     * are claims about work actually performed, not about the schedule. Used by a driver-confirmed
     * interval edit (voice `set_maintenance_interval`, ticket 05) and by an accepted advisor
     * proposal (`AdvisorProposalExecutor.setMaintenanceItem`) - both pass `source = "CONFIRMED"`,
     * because ticket 05's rule is that only the factory populate may touch a driver-owned interval
     * from here on, and both of THOSE paths are the explicit confirmation that rule requires.
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law). A zero
     * means the `(vehicleId, serviceName)` pair has no row; the caller creates one via [upsert]
     * instead, deliberately, rather than this silently doing nothing.
     */
    @Query(
        "UPDATE maintenance_items SET intervalMiles = :miles, intervalMonths = :months, " +
            "intervalSource = :source, updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName"
    )
    suspend fun setIntervals(vehicleId: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int

    /**
     * Edits ONLY the "when was this last done" anchor - [MaintenanceItem.lastDoneMileage]/
     * [MaintenanceItem.lastDoneDate] - and clears [MaintenanceItem.neverDone] back to false, since
     * supplying a real anchor is the driver un-confirming a prior "never done" (see
     * [com.kevin.legion.vehicle.VehicleController.mergeBackfillAnchors]'s doc for the same rule
     * applied in Kotlin before this write existed). Never touches the interval columns - this is
     * exactly the read-modify-write [logServiceDirect] used to do by hand, closed into one UPDATE
     * so a concurrent interval edit can't be lost between the read and the write.
     *
     * [mileage]/[date] are nullable so ONE axis can be supplied while the other is deliberately
     * cleared (the caller resolves that merge - see [com.kevin.legion.vehicle.VehicleController
     * .mergeBackfillAnchors]) - passing both null clears both, which is a legitimate "I don't know"
     * anchor distinct from `neverDone`.
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law).
     */
    @Query(
        "UPDATE maintenance_items SET lastDoneMileage = :mileage, lastDoneDate = :date, " +
            "neverDone = 0, updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName"
    )
    suspend fun setAnchor(vehicleId: String, serviceName: String, mileage: Int?, date: Long?, now: Long): Int

    /**
     * Marks [MaintenanceItem.neverDone] and clears both anchor columns - "never done" REPLACES any
     * prior guess, it does not add to one (see [com.kevin.legion.vehicle.VehicleController
     * .mergeBackfillAnchors]'s doc). Never touches the interval columns.
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law).
     */
    @Query(
        "UPDATE maintenance_items SET neverDone = 1, lastDoneMileage = NULL, lastDoneDate = NULL, " +
            "updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName"
    )
    suspend fun setNeverDone(vehicleId: String, serviceName: String, now: Long): Int

    /**
     * Clears [MaintenanceItem.neverDone] back to false ONLY - never touches the anchor or interval
     * columns. Engine retirement step 3 (ticket 16): `FleetEngineStore.setAnchor`/
     * `.setNeverDoneCleared` need this as an isolated targeted write because, post-repoint, the
     * anchor itself is DERIVED from `service_records` rather than stored here (see
     * [com.kevin.legion.engine.fleet.FleetRecordBridge.projectAnchorLegacy]'s own doc) - the old
     * combined [setAnchor] query below still writes the (now-dead, kept-but-unused) `lastDoneMileage`/
     * `lastDoneDate` columns alongside `neverDone`, which is harmless but not what a repointed
     * caller wants to reach for.
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law).
     */
    @Query("UPDATE maintenance_items SET neverDone = 0, updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName")
    suspend fun clearNeverDone(vehicleId: String, serviceName: String, now: Long): Int

    /**
     * Tombstones an item rather than deleting the row (ticket 07,
     * `.scratch/fleet-maintenance/issues/07-hand-added-items-and-what-delete-means.md`).
     * `maintenance_items` syncs `Mode.LWW, naturalPk = true` (`SyncEngine.kt`), so a hard DELETE
     * cannot propagate - the other device's un-deleted copy would win the next merge and resurrect
     * the row. Same pattern `car_tasks`/`places` have carried since B19: [getForVehicle]/[get]
     * below filter `deleted = 0` for every ordinary reader, and [com.kevin.legion.sync.SyncEngine]'s
     * raw-SQL snapshot deliberately does NOT filter on this column, so the tombstone ships to Drive
     * and a newer `deleted = 1` wins through the normal LWW path like any other edit.
     *
     * `ServiceRecord` history for the item is untouched - deleting a schedule row does not un-do
     * work that was actually logged (ticket 07 decision 1).
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law).
     */
    @Query("UPDATE maintenance_items SET deleted = 1, updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName")
    suspend fun softDelete(vehicleId: String, serviceName: String, now: Long): Int

    /**
     * Un-tombstones a row AND sets its interval in one targeted write (ticket 14,
     * `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`'s "you deleted
     * this - add it back?" case: a populate finds a factory item that matches a TOMBSTONED row on
     * file). One UPDATE rather than restore-then-[setIntervals] as two calls, for the same
     * no-read-modify-write reason every other targeted write here gives - a concurrent edit between
     * two separate writes could otherwise land in an inconsistent order. `source` is always
     * `"CONFIRMED"` at the one call site (ticket 06 decision b: accepting a populate diff row is the
     * driver's own confirmation), but is threaded as a parameter rather than hardcoded here to match
     * every other write in this file, which never bakes a literal into its own SQL.
     *
     * **Returns the affected row count - the caller MUST check it** (ticket 05's law): a restore
     * against a `(vehicleId, serviceName)` pair with no row at all (never tombstoned, or genuinely
     * absent) touches zero rows and must be reported as a failure, not assumed to have worked.
     */
    @Query(
        "UPDATE maintenance_items SET deleted = 0, intervalMiles = :miles, intervalMonths = :months, " +
            "intervalSource = :source, updatedAt = :now WHERE vehicleId = :vehicleId AND serviceName = :serviceName"
    )
    suspend fun restore(vehicleId: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int

    /**
     * Active items only - filters the ticket 07 tombstone. Every reader EXCEPT
     * [com.kevin.legion.sync.SyncEngine]'s raw-SQL snapshot must go through here or [get], never a
     * bare `SELECT * FROM maintenance_items` - see [softDelete]'s doc for why the sync path is the
     * one deliberate exception.
     */
    @Query("SELECT * FROM maintenance_items WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun getForVehicle(vehicleId: String): List<MaintenanceItem>

    /**
     * EVERY row for [vehicleId], tombstoned included - the second deliberate exception alongside
     * [com.kevin.legion.sync.SyncEngine]'s snapshot, and for a related reason: ticket 14's populate
     * diff has to tell "on file, factory doesn't list it" (an active row with no factory match) apart
     * from "you deleted this, and the factory lists it" (a TOMBSTONED row with a factory match) -
     * [getForVehicle]'s `deleted = 0` filter would make the second case indistinguishable from "would
     * add", silently re-adding something the driver deliberately removed. Never used for an ordinary
     * read; only the populate-diff builder in `vehicle/PopulateSchedule.kt` calls this.
     */
    @Query("SELECT * FROM maintenance_items WHERE vehicleId = :vehicleId")
    suspend fun getForVehicleIncludingDeleted(vehicleId: String): List<MaintenanceItem>

    /** Active item only - see [getForVehicle]'s doc on the `deleted = 0` filter. */
    @Query("SELECT * FROM maintenance_items WHERE vehicleId = :vehicleId AND serviceName = :serviceName AND deleted = 0")
    suspend fun get(vehicleId: String, serviceName: String): MaintenanceItem?
}
