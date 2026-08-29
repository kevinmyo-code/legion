package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

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
 * turns up no fleet-related file at all (the hits are all notes/dates/calls/ledger).
 *
 * **CORRECTED 2026-08-29, ticket 26 (`.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`):
 * this table now HAS a live writer** - [com.kevin.legion.vehicle.FleetEngineStore]'s
 * `syncVehicleToServer` upserts a row here on every configured vehicle identity write. [upsert]
 * still reads by [serverId] first and reuses any existing row's [id] rather than blindly
 * REPLACE-ing (the exact trap [Event]'s own doc comment and `b17bc88` warn about), even though the
 * trace above still finds no consumer of [id] to protect today - the caution costs one extra
 * `SELECT`, and building the habit now is cheaper than re-learning `b17bc88` a second time the day
 * a vehicle-scoped alarm or notification DOES get wired to this column.
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
 * **CORRECTED 2026-08-29, ticket 26.** No longer "minus the carried-id upsert machinery" - [update]
 * plus the top-level [upsert] extension function below give this DAO the identical
 * "read-by-serverId-first, reuse the existing local id" shape [EventDao.upsert] established for
 * [Event], now that [com.kevin.legion.vehicle.FleetEngineStore] is a genuine live caller.
 * [com.kevin.legion.backend.FleetReconcile]'s own wipe-and-refill still uses [insert] directly
 * (unchanged, out of this ticket's scope) - the two writers do not collide because reconcile only
 * ever runs against an empty table right after [deleteAllForReplicaRefresh].
 */
@Dao
interface VehicleReplicaDao {
    /** Active (not tombstoned) vehicles - what the CONFIGURED read path in
     * [com.kevin.legion.vehicle.FleetEngineStore] composes with [VehicleSidecar]. */
    @Query("SELECT * FROM vehicles_replica WHERE deleted = 0")
    suspend fun getAllActive(): List<VehicleReplica>

    /** Every row including tombstones - used only by [com.kevin.legion.backend.FleetReconcile] to
     * diff against the server's own active set, same shape as [EventReplicaDao.getAll]. */
    @Query("SELECT * FROM vehicles_replica")
    suspend fun getAll(): List<VehicleReplica>

    @Query("SELECT * FROM vehicles_replica WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): VehicleReplica?

    @Query("SELECT * FROM vehicles_replica WHERE id = :id")
    suspend fun getById(id: Long): VehicleReplica?

    @Insert
    suspend fun insert(row: VehicleReplica): Long

    @Update
    suspend fun update(row: VehicleReplica)

    /** Wipes the replica clean before [com.kevin.legion.backend.FleetReconcile] refills it - same
     * role as [EventReplicaDao.deleteAllForReplicaRefresh]. Never called from a regular read/write
     * path. Safe to wipe-and-refill with a fresh autoincrement [VehicleReplica.id] every time
     * because nothing in the app addresses a row here by that id - see [VehicleReplica]'s own doc
     * comment for the trace that established this. */
    @Query("DELETE FROM vehicles_replica")
    suspend fun deleteAllForReplicaRefresh()
}

/**
 * Same "read by [VehicleReplica.serverId] first, reuse the existing row's [VehicleReplica.id]"
 * shape as [EventDao.upsert] - see that function's own doc comment for the full reasoning and the
 * `b17bc88` incident it exists to never repeat. Used only by
 * [com.kevin.legion.vehicle.FleetEngineStore]'s `syncVehicleToServer`, the one live caller ticket
 * 26 adds - [com.kevin.legion.backend.FleetReconcile] keeps using [VehicleReplicaDao.insert]
 * directly against an always-empty (just-wiped) table, so the two writers never contend.
 */
suspend fun VehicleReplicaDao.upsert(row: VehicleReplica): Long {
    val existing = getByServerId(row.serverId)
    if (existing != null) {
        update(row.copy(id = existing.id))
        return existing.id
    }
    return insert(row)
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
