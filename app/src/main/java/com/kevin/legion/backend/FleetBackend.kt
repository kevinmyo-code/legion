package com.kevin.legion.backend

/**
 * A `public.vehicles` row as Postgres reports it
 * (`supabase/migrations/20260825000500_aspect_places_fleet.sql`) - the shape
 * [SupabaseFleetBackend] hands back after every write, and the shape [FleetReconcile] would copy
 * into a Room replica once one exists (see that object's own class doc for why vehicle/
 * service-history replica writes are NOT part of this wave).
 *
 * [odometerBaseline]/[odometerBaselineAtMs] are paired-or-neither at the database level
 * (`vehicles_odometer_baseline_paired`), mirrored here as two independently-nullable fields rather
 * than one combined type only because every other Remote* shape in this package does the same
 * (see [RemoteEvent]'s many nullable pairs) - the pairing invariant is the SERVER's to enforce, not
 * this DTO's.
 *
 * [updatedAtMs] is the server's own `updated_at` - the "as of" clock for the cache-first read path
 * (ticket 01 ruling 9), same role as [RemotePlace.updatedAtMs]. [originGuid] is the phase 4
 * migration-provenance column (`supabase/migrations/20260826000100_origin_guid.sql`) - null for a
 * vehicle created directly against the server, set only on a row [FleetBackend.uploadMigratedVehicle]
 * wrote. [FleetReconcile]'s diff reads it the same way [EventsReconcile]/[PantryReconcile] do.
 */
data class RemoteVehicle(
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
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String?,
)

/**
 * One already-seeded engine `Vehicle` record, ready for the one-time migration upload
 * ([FleetBackend.uploadMigratedVehicle]). [originGuid] is the record's own `records.guid`
 * (`EngineRecord.guid`) - the same idempotency key every other Phase 4 aspect uses.
 *
 * **No live upsert-by-serverId exists yet, deliberately** - unlike [RemoteEvent]'s
 * [EventFields]/[EventsBackend.upsert] pair, this wave has no production caller wiring
 * `vehicle/VehicleController.kt` onto the server (that is later work per ticket 10's own "later
 * waves add the rest"). Building an untested, uncalled live-edit path now would be exactly the
 * scope creep CLAUDE.md's "no false success" posture warns against - a method with no caller and
 * no test is not established shape, it is speculation wearing the pattern's clothes.
 */
data class MigratedVehicle(
    val originGuid: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String?,
    val engine: String?,
    val confirmed: Boolean,
    val odometerBaseline: Int?,
    val odometerBaselineAtMs: Long?,
)

/**
 * A `public.service_history` row as Postgres reports it. [vehicleServerId] is the SERVER's own
 * `vehicles.id` (a uuid), never the engine's `Vehicle` record id and never the phone's `obdMac` -
 * [FleetReconcile] is the one place that translates between the three, because only it holds the
 * map from an engine `Vehicle` record's id to the server row [FleetBackend.uploadMigratedVehicle]
 * produced for it.
 *
 * [kind] is `"OBSERVED"` or `"ASSERTED"` (`FleetAspectSeeder.KIND_OPTIONS`) - **never confused with
 * `provenance`**, which this DTO does not even carry: every row this wave uploads is a phone-side
 * fact migrated verbatim, so provenance is uniformly `USER` server-side and there is nothing for a
 * caller here to branch on. `kind` is the one distinction that matters to a reader.
 */
data class RemoteServiceHistory(
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
 * One already-seeded engine `ServiceHistory` record, ready for
 * [FleetBackend.uploadMigratedServiceHistory]. [vehicleServerId] must already be resolved to the
 * server's own vehicle uuid before this is constructed - see [FleetReconcile]'s own doc comment for
 * why a `ServiceHistory` row can never upload ahead of its vehicle.
 */
data class MigratedServiceHistory(
    val originGuid: String,
    val vehicleServerId: String,
    val serviceName: String,
    val mileage: Int?,
    val serviceDateEpochMs: Long?,
    val costCents: Long?,
    val kind: String,
)

/**
 * A `public.drives` row as Postgres reports it (`supabase/migrations/20260826000200_fleet_drives.sql`).
 * [syncId] is [com.kevin.legion.data.local.Drive.syncId] carried verbatim - drives are NOT engine
 * records (`FleetAspectSeeder` defines only Vehicle/ServiceHistory/MaintenanceSchedule), so there is
 * no `records.guid` to key on and none of this type's siblings' `originGuid` machinery applies here.
 *
 * [gallons] is nullable with no zero default, carrying [com.kevin.legion.data.local.Drive.gallons]'
 * own rule forward: unknown fuel and no fuel are different facts, and collapsing them makes MPG lie.
 */
data class RemoteDrive(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val miles: Double,
    val gallons: Double?,
    val endReason: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/**
 * One [com.kevin.legion.data.local.Drive] row, ready for [FleetBackend.upsertDrive].
 * [vehicleServerId] is already resolved to the server's vehicle uuid - see [RemoteServiceHistory]'s
 * own doc comment for why that translation belongs to [FleetReconcile], not this DTO.
 *
 * **Unlike [MigratedVehicle]/[MigratedServiceHistory], this is a genuine upsert, not a
 * check-then-insert migration record** - `drives.sync_id` is `NOT NULL UNIQUE` server-side, exactly
 * the same natural-key shape as [PlacesBackend.upsert]'s `label`, so Postgres's own `ON CONFLICT`
 * does the idempotency work and there is nothing to check for first.
 */
data class DriveUpload(
    val syncId: String,
    val vehicleServerId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val miles: Double,
    val gallons: Double?,
    val endReason: String,
)

/**
 * The Phase 4 fleet seam for THIS wave's three tables (`vehicles`, `service_history`, `drives`) -
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`. Mirrors [PlacesBackend]/[EventsBackend]'s shape
 * exactly: narrow, no [io.github.jan.supabase.SupabaseClient] in any signature, every function
 * returns [Result] rather than throwing or returning a nullable.
 *
 * **The identity split that makes this interface interesting.** `vehicles` and `service_history`
 * are engine records (`FleetAspectSeeder` defines Vehicle, ServiceHistory, MaintenanceSchedule), so
 * they upload keyed on `origin_guid` from `records.guid`, exactly like [EventsBackend.uploadMigratedEvent]/
 * [PantryBackend.uploadMigratedReceipt]. `drives` is NOT an engine record - `Drive.syncId` was
 * already the portable cross-device identity before any of this existed, so it upserts by that
 * natural key instead, exactly like [PlacesBackend.upsert]'s `label`. Two different idempotency
 * shapes in one aspect, because the two halves of the aspect have genuinely different identity
 * histories, not because this interface picked one arbitrarily.
 */
interface FleetBackend {
    /** Every active (not soft-deleted) vehicle, server-side. Used to refresh a Room replica once
     * one exists, and (this wave) to resolve an engine `Vehicle` record's server-side uuid for
     * uploading its `ServiceHistory`/`Drive` children. Never called from a hot-path read. */
    suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>>

    /** The one-time migration upload for an engine `Vehicle` record not yet mirrored server-side.
     * `Result.success(false)` means a row with this [MigratedVehicle.originGuid] was already present
     * (a re-run, per ticket 05 phase 4 step 1: "a re-run is free"). `Result.failure` means the
     * request itself did not complete. */
    suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean>

    /** Every active (not soft-deleted) service-history row, server-side. Used to refresh a Room
     * replica once one exists. Never called from a hot-path read. */
    suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>>

    /** The one-time migration upload for an engine `ServiceHistory` record. Same idempotency
     * contract as [uploadMigratedVehicle]. [MigratedServiceHistory.vehicleServerId] must already
     * name a vehicle this backend has accepted - a foreign key violation here is not translated
     * into a friendlier failure, because it means [FleetReconcile] tried to upload a service-history
     * row ahead of its vehicle, which is a caller bug, not an expected outcome. */
    suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean>

    /** Every active (not soft-deleted) drive, server-side. Used to refresh the Room replica (the
     * `drives` table itself - see [FleetReconcile]'s own class doc for why no separate replica
     * table is needed here). Never called from a hot-path read. */
    suspend fun fetchActiveDrives(): Result<List<RemoteDrive>>

    /** Upserts by [DriveUpload.syncId] (`drives.sync_id`'s own unique constraint) - reposts of an
     * already-uploaded drive are free, matching [PlacesBackend.upsert]'s reactivation shape. A
     * drive, once finalised, never changes (`Drive`'s own class doc: "no update, no delete"), so
     * every call here is either a genuinely new drive or a harmless no-op re-upload, never an edit. */
    suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseFleetBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as [PlacesBackendException]/
 * [EventsBackendException]. */
class FleetBackendException(message: String) : Exception(message)
