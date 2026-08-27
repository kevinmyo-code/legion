package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The Room replica of a `public.vehicles` row (backend-erp fleet wave 2,
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`'s own follow-up - wave 1 left this table off
 * entirely, see [com.kevin.legion.backend.FleetReconcile]'s pre-wave-2 class doc for why the legacy
 * `Vehicle` table could not serve as its own replica). Mirrors [com.kevin.legion.backend.RemoteVehicle]
 * field for field - see that data class's own doc comment for the source of truth this caches, and
 * `supabase/migrations/20260825000500_aspect_places_fleet.sql` for the server table it mirrors.
 * Deliberately does NOT carry `provenance` - [com.kevin.legion.backend.RemoteVehicle] itself does
 * not either, matching [EventReplica]'s own precedent of leaving that server-only column off the
 * cache.
 *
 * **[id] IS a plain autoincrement surrogate, refilled wholesale on every reconcile, and that is
 * deliberately simpler than [EventReplica]'s carried-id dance.** [EventReplica]'s doc comment (and
 * `b17bc88`'s own postmortem) explains at length why THAT table cannot use a naive wipe-and-refill:
 * `ListItem.id`/`EventReplica.id` is load-bearing as an alarm `PendingIntent` request code. This
 * table has no equivalent exposure - **traced, not assumed** - before choosing autoincrement:
 * grepping `vehicle/`, `engine/fleet/`, and `ui/fleet/` for `PendingIntent`/`requestCode`/
 * `notificationId` turns up nothing, and grepping the whole app for `NotificationManager`/`notify(`
 * turns up no fleet-related file at all (the hits are all notes/dates/calls/ledger). Nothing in this
 * wave repoints a live read at this replica yet either (that is later work, per the fleet-cutover
 * ticket's own "later waves add the rest"), so there is no consumer of [id] to protect. If a later
 * wave wires a vehicle-scoped alarm or notification to this table's [id], THAT is the moment to
 * port [EventReplica]'s carried-id upsert over - doing it now would be exactly the "mechanism whose
 * reason is not understood" `b17bc88`'s lesson warns against building reflexively.
 */
@Entity(
    tableName = "vehicles_replica",
    indices = [Index("serverId", unique = true)],
)
data class VehicleReplica(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String?,
    val engine: String?,
    val confirmed: Boolean,
    val odometerBaseline: Int?,
    val odometerBaselineAtMs: Long?,
    /** The server's own `updated_at` - same "as of" clock role as [EventReplica.updatedAtMs]. */
    val updatedAtMs: Long,
    val deleted: Boolean,
    /** Null for a vehicle created directly against the server; set to the originating engine
     * record's `records.guid` for a migrated row - same convention as [EventReplica] has no
     * equivalent field for (Notes+Dates never needed one) but [com.kevin.legion.backend.RemoteVehicle.originGuid]
     * itself carries, so it is kept here rather than dropped, in case a later wave needs to trace a
     * replica row back to the engine record it came from. */
    val originGuid: String?,
)

/**
 * The Room replica of a `public.service_history` row - same wave and reasoning as [VehicleReplica].
 * Mirrors [com.kevin.legion.backend.RemoteServiceHistory] field for field. [vehicleServerId] is the
 * SERVER's own `vehicles.id` uuid, exactly as on the wire shape - never the engine `Vehicle` record
 * id and never the legacy `obdMac`, matching [com.kevin.legion.backend.RemoteServiceHistory.vehicleServerId]'s
 * own doc comment.
 *
 * [kind] is `"OBSERVED"` or `"ASSERTED"` - never confused with `provenance`, which (like
 * [VehicleReplica]) this table does not carry, because every uploaded row today is a phone-side fact
 * migrated verbatim and `provenance` server-side is uniformly `USER`. [id] is the same plain
 * autoincrement-on-refill surrogate as [VehicleReplica.id], for the identical reason: no alarm,
 * notification, or foreign key anywhere in the app addresses a `ServiceRecord`/service-history row
 * by a stable local id (the legacy `ServiceRecord` table's own autoincrement `id` has no such
 * consumer either - `grep`'d the same way as [VehicleReplica]'s doc comment describes).
 */
@Entity(
    tableName = "service_history_replica",
    indices = [Index("serverId", unique = true)],
)
data class ServiceHistoryReplica(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val vehicleServerId: String,
    val serviceName: String,
    val mileage: Int?,
    val serviceDateEpochMs: Long?,
    val costCents: Long?,
    val kind: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String?,
)

/**
 * Same shape as [EventReplicaDao] minus the carried-id [upsert]/[getById] machinery - see
 * [VehicleReplica]'s own doc comment for why a plain wipe-and-refill is the right amount of
 * mechanism here, not a shortcut taken past it.
 */
@Dao
interface VehicleReplicaDao {
    /** Active (not tombstoned) vehicles - what a future CONFIGURED read path would render from.
     * No live caller yet (repointing reads is a later wave, per the fleet-cutover ticket). */
    @Query("SELECT * FROM vehicles_replica WHERE deleted = 0")
    suspend fun getAllActive(): List<VehicleReplica>

    /** Every row including tombstones - used only by [com.kevin.legion.backend.FleetReconcile] to
     * diff against the server's own active set, same shape as [EventReplicaDao.getAll]. */
    @Query("SELECT * FROM vehicles_replica")
    suspend fun getAll(): List<VehicleReplica>

    @Query("SELECT * FROM vehicles_replica WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): VehicleReplica?

    @Insert
    suspend fun insert(row: VehicleReplica): Long

    /** Wipes the replica clean before [com.kevin.legion.backend.FleetReconcile] refills it - same
     * role as [EventReplicaDao.deleteAllForReplicaRefresh]. Never called from a regular read/write
     * path. Safe to wipe-and-refill with a fresh autoincrement [VehicleReplica.id] every time
     * because nothing in the app addresses a row here by that id - see [VehicleReplica]'s own doc
     * comment for the trace that established this. */
    @Query("DELETE FROM vehicles_replica")
    suspend fun deleteAllForReplicaRefresh()
}

@Dao
interface ServiceHistoryReplicaDao {
    @Query("SELECT * FROM service_history_replica WHERE deleted = 0")
    suspend fun getAllActive(): List<ServiceHistoryReplica>

    @Query("SELECT * FROM service_history_replica")
    suspend fun getAll(): List<ServiceHistoryReplica>

    @Query("SELECT * FROM service_history_replica WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): ServiceHistoryReplica?

    @Insert
    suspend fun insert(row: ServiceHistoryReplica): Long

    /** Same role and same safety argument as [VehicleReplicaDao.deleteAllForReplicaRefresh]. */
    @Query("DELETE FROM service_history_replica")
    suspend fun deleteAllForReplicaRefresh()
}
