package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ServiceRecord].
 *
 * **Every reader below filters `deleted = 0`** (ticket 11 §2, v20->v21's tombstone column) except
 * [getById] (an edit form loaded from an already-`deleted = 0`-filtered list has no business 404ing
 * on a race, and there is no second caller of it that needs the filter) - the same "ordinary readers
 * filter, sync's raw-SQL snapshot doesn't" split [MaintenanceItemDao] already uses. **This
 * tombstone does NOT propagate across devices** - see [ServiceRecord.deleted]'s own doc comment for
 * why `Mode.UNION` makes that structurally impossible, unlike `maintenance_items`' LWW tombstone.
 */
@Dao
interface ServiceRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ServiceRecord)

    @Query("SELECT * FROM service_records WHERE deleted = 0 ORDER BY date DESC")
    fun getAllRecords(): Flow<List<ServiceRecord>>

    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY date DESC")
    fun getRecordsForVehicle(vehicleId: String): Flow<List<ServiceRecord>>

    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY date DESC LIMIT 1")
    suspend fun getMostRecentForVehicle(vehicleId: String): ServiceRecord?

    /**
     * The most recent non-deleted record for ONE named service (ticket 28,
     * `.scratch/hands-and-senses/issues/28-the-oil-change-it-forgot.md`) - feeds
     * [com.kevin.legion.vehicle.MaintenanceAgent]'s "last done" composition, which
     * must consult this table rather than trusting `maintenance_items`' anchor
     * columns alone. [serviceName] must be the item's own stored name (the same
     * string `logServiceDirect`/`logPastServiceDirect` file records under - see
     * their own comments on `targetName`), never the driver's raw phrasing; an
     * exact match is correct here because every write path already canonicalises
     * to the schedule item's name before either table is touched. Plain `@Query`,
     * no schema change - Room needs no migration for a new read.
     */
    @Query(
        "SELECT * FROM service_records WHERE vehicleId = :vehicleId AND serviceName = :serviceName " +
            "AND deleted = 0 ORDER BY date DESC LIMIT 1"
    )
    suspend fun getMostRecentForVehicleAndService(vehicleId: String, serviceName: String): ServiceRecord?

    /** One-shot recent history for the maintenance worker (no Flow to collect). */
    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentForVehicle(vehicleId: String, limit: Int): List<ServiceRecord>

    /**
     * One record by id, for the edit form (ticket 11 §2) to load its current mileage/cost from
     * before the driver changes them. Deliberately NOT filtered on `deleted` - see this interface's
     * own doc comment.
     */
    @Query("SELECT * FROM service_records WHERE id = :id")
    suspend fun getById(id: Long): ServiceRecord?

    /**
     * Total logged maintenance spend, in cents, over NON-DELETED rows only (ignores null costs) -
     * feeds the build-sheet grand total and [com.kevin.legion.vehicle.FleetSpendController]. Cents,
     * never dollars (CLAUDE.md §4 rule 3) - callers that combine this with a dollar figure (e.g.
     * [BuildEntry.cost]) must divide by 100 themselves; this DAO stays in the same unit as the
     * column it sums.
     */
    @Query("SELECT COALESCE(SUM(costCents), 0) FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun totalCost(vehicleId: String): Long

    /**
     * How many non-deleted records actually carry a cost, versus [countForVehicle]'s total (ticket
     * 11 §4: "must state how many records it covers" - a total that silently omits cost-less records
     * is a lie by omission of exactly CLAUDE.md §4 rule 6's shape). Feeds
     * [com.kevin.legion.vehicle.FleetSpendController.totalSpent]'s coverage wording.
     */
    @Query("SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0 AND costCents IS NOT NULL")
    suspend fun countWithCost(vehicleId: String): Int

    /** Count of services logged in a time range - feeds MonthlyRecapController's aggregation. */
    @Query(
        "SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0 " +
            "AND date >= :fromMs AND date <= :toMs"
    )
    suspend fun countInRange(vehicleId: String, fromMs: Long, toMs: Long): Int

    /** Total records for a vehicle - ticket 09's FLEET "NOT BUILT YET" block needs a real count, not a hardcoded one. */
    @Query("SELECT COUNT(*) FROM service_records WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun countForVehicle(vehicleId: String): Int

    /**
     * True if a precise, actually-logged service exists at or after [atOrAfterMs] - ticket 08's
     * backfill-conflict rule (`.scratch/fleet-maintenance/issues/08-matching-a-logged-service-to-an-item.md`).
     * Real damage this closes: on Kevin's device a `log_service` wrote a record AND its anchor at
     * 118,374; fourteen seconds later a `log_past_service` backfill silently overwrote that anchor
     * to 118,483 and nulled its date - a remembered approximation beat a precise fact. [serviceName]
     * must be the SAME name the anchor write would use (the caller's already-matched/canonicalised
     * name, never the driver's raw phrasing) or this cannot see the record it exists to protect.
     * `deleted = 0` (ticket 11 §2): a record Kevin deleted as a mistake has no business blocking a
     * legitimate backfill.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM service_records WHERE vehicleId = :vehicleId " +
            "AND serviceName = :serviceName AND date >= :atOrAfterMs AND deleted = 0)"
    )
    suspend fun hasRecordAtOrAfter(vehicleId: String, serviceName: String, atOrAfterMs: Long): Boolean

    /**
     * The edit form's SAVE (ticket 11 §2) - a targeted write touching ONLY `mileage`/`costCents`,
     * mirroring [MaintenanceItemDao.setIntervals]' shape (the form always shows both fields, pre-
     * filled with the current row, so both are written unconditionally rather than merged against a
     * stale read). Returns the affected row count - ticket 05's law, restated here: the caller must
     * surface a `0` as a real failure ("it may have just been deleted"), never assume success.
     */
    @Query("UPDATE service_records SET mileage = :mileage, costCents = :costCents WHERE id = :id AND deleted = 0")
    suspend fun editMileageAndCost(id: Long, mileage: Int, costCents: Long?): Int

    /**
     * The soft-delete tombstone (ticket 11 §2). Present, not absent, after this runs - same
     * `deleted = 1` shape [MaintenanceItemDao.softDelete] uses - but **this one is LOCAL ONLY and
     * cannot propagate to another device**; see [ServiceRecord.deleted]'s own doc comment for why
     * `service_records`' `Mode.UNION` sync makes that structurally different from
     * `maintenance_items`' LWW tombstone. Returns the affected row count (ticket 05's law); a `0`
     * means the id was already gone (already deleted, or never existed) and must be surfaced as a
     * real failure, not assumed.
     */
    @Query("UPDATE service_records SET deleted = 1 WHERE id = :id AND deleted = 0")
    suspend fun softDelete(id: Long): Int
}
