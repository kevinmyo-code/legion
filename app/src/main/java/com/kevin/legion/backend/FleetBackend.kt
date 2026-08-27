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
 * A `public.code_events` row as Postgres reports it (`supabase/migrations/20260826000600_fleet_diagnostics_specs_build.sql`).
 * [syncId] is [com.kevin.legion.data.local.CodeEvent.syncId] carried verbatim - like [RemoteDrive],
 * this table has no engine-record counterpart, so it follows the drives pattern, not the vehicles
 * one. [codesJson]/[freezeFrameJson] stay raw JSON TEXT at this interface boundary (never
 * `JsonElement`) - the same choice [RemoteEvent.structuredMeta]'s own doc comment explains for
 * `EventFields`: only [SupabaseFleetBackend] needs to know these are `jsonb` server-side.
 * [freezeFrameJson] is `null`, never `""`, when the adapter returned no freeze frame - the phone's
 * `""`-means-absent convention is translated to a real absence by [FleetReconcile] before this type
 * is ever constructed, so a test against a fake backend can assert the translation happened without
 * needing a live Postgres connection to prove it.
 */
data class RemoteCodeEvent(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val occurredAtMs: Long,
    val mileage: Int?,
    val codesJson: String,
    val freezeFrameJson: String?,
    val provenance: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** One [com.kevin.legion.data.local.CodeEvent] row, ready for [FleetBackend.upsertCodeEvent] - same
 * "genuine upsert by natural key, nothing to check for first" shape as [DriveUpload].
 *
 * [provenance] is asserted here, by [FleetReconcile], not left to the server column's own default -
 * CLAUDE.md section 4 rule 4 ("tag the provenance of every row") is a claim the PHONE makes, because
 * the phone is the thing that actually knows a dongle produced this row. A constant value is still
 * an explicit one; the alternative (omitting the column) would be the database guessing on the
 * client's behalf, correct today only by coincidence. Always `"DETERMINISTIC"` for this table - see
 * [RemoteCodeEvent]'s own doc comment. */
data class CodeEventUpload(
    val syncId: String,
    val vehicleServerId: String,
    val occurredAtMs: Long,
    val mileage: Int?,
    val codesJson: String,
    val freezeFrameJson: String?,
    val provenance: String,
)

/**
 * A `public.code_clear_events` row as Postgres reports it. [codesAfterJson] carries
 * [com.kevin.legion.data.local.CodeClearEvent.codesAfterJson]'s three-way distinction translated to
 * real absence: `null` means the re-read never completed (UNVERIFIED - the phone's `""`), a JSON
 * array text of `"[]"` means the re-read ran clean (CLEARED), and a non-empty JSON array text names
 * RETURNED's survivors. Collapsing `null` and `"[]"` here would erase exactly the distinction
 * `CodeClearEvent.kt`'s own doc comment calls load-bearing, so it is not collapsed.
 */
data class RemoteCodeClearEvent(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val occurredAtMs: Long,
    val mileage: Int?,
    val codesBeforeJson: String,
    val freezeFrameJson: String?,
    val codesAfterJson: String?,
    val outcome: String,
    val ackRaw: String,
    val provenance: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** One [com.kevin.legion.data.local.CodeClearEvent] row, ready for
 * [FleetBackend.upsertCodeClearEvent] - same upsert-by-syncId shape as [CodeEventUpload].
 * [provenance] is asserted by [FleetReconcile] the same way - see [CodeEventUpload]'s own doc
 * comment for why a constant still has to be sent explicitly. Always `"DETERMINISTIC"`. */
data class CodeClearEventUpload(
    val syncId: String,
    val vehicleServerId: String,
    val occurredAtMs: Long,
    val mileage: Int?,
    val codesBeforeJson: String,
    val freezeFrameJson: String?,
    val codesAfterJson: String?,
    val outcome: String,
    val ackRaw: String,
    val provenance: String,
)

/**
 * A `public.oil_analyses` row as Postgres reports it. [provenance] is always `"USER"` here -
 * `OilAnalysis.kt`'s own doc comment says these are voice-entered or typed by the driver
 * transcribing a lab report, the one place this wave's provenance choice diverges from its three
 * DETERMINISTIC siblings (`code_events`/`code_clear_events`/`chassis_quirks`), per the migration
 * file's own header. Every ppm/percent field is nullable with no zero default: `null` means the lab
 * did not report that element, never that it measured zero of it.
 */
data class RemoteOilAnalysis(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val analyzedAtMs: Long,
    val mileage: Int?,
    val oilBrand: String,
    val oilGrade: String,
    val drainIntervalMiles: Int?,
    val iron: Int?,
    val copper: Int?,
    val lead: Int?,
    val tin: Int?,
    val aluminum: Int?,
    val chromium: Int?,
    val nickel: Int?,
    val sodium: Int?,
    val potassium: Int?,
    val silicon: Int?,
    val boron: Int?,
    val magnesium: Int?,
    val fuelPercent: Double?,
    val waterPercent: Double?,
    val tbn: Double?,
    val viscosityCst: Double?,
    val labNotes: String,
    val provenance: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** One [com.kevin.legion.data.local.OilAnalysis] row, ready for [FleetBackend.upsertOilAnalysis] -
 * same upsert-by-syncId shape as [CodeEventUpload]. [provenance] is asserted by [FleetReconcile] -
 * always `"USER"` here, the one place this wave's provenance choice diverges from its
 * DETERMINISTIC siblings (see [RemoteOilAnalysis]'s own doc comment): a person transcribed a lab
 * report, code did not derive these numbers. */
data class OilAnalysisUpload(
    val syncId: String,
    val vehicleServerId: String,
    val analyzedAtMs: Long,
    val mileage: Int?,
    val oilBrand: String,
    val oilGrade: String,
    val drainIntervalMiles: Int?,
    val iron: Int?,
    val copper: Int?,
    val lead: Int?,
    val tin: Int?,
    val aluminum: Int?,
    val chromium: Int?,
    val nickel: Int?,
    val sodium: Int?,
    val potassium: Int?,
    val silicon: Int?,
    val boron: Int?,
    val magnesium: Int?,
    val fuelPercent: Double?,
    val waterPercent: Double?,
    val tbn: Double?,
    val viscosityCst: Double?,
    val labNotes: String,
    val provenance: String,
)

/**
 * A `public.chassis_quirks` row as Postgres reports it. **No `vehicleServerId`, unlike this wave's
 * other three tables** - `chassis_quirks` is household-shared reference data parsed from a bundled
 * JSON asset, not a per-vehicle observation (the migration's own comment: "hence no vehicle_id").
 * [mileageLow]/[mileageHigh]/[costLowCents]/[costHighCents] are `null` for "no bound"/"unknown" -
 * the phone's `-1` sentinel (`ChassisQuirk.mileageLow`/`costLow` etc.) is translated to a real
 * absence by [FleetReconcile] before this type is ever constructed, same posture as
 * [RemoteCodeEvent.freezeFrameJson]. Cost is cents (`Long`), converted from the phone's USD `Int` at
 * the same boundary - CLAUDE.md section 3.
 */
data class RemoteChassisQuirk(
    val quirkId: String,
    val chassis: String,
    val engine: String,
    val title: String,
    val symptom: String,
    val verificationSteps: String,
    val mileageLow: Int?,
    val mileageHigh: Int?,
    val severity: String,
    val costLowCents: Long?,
    val costHighCents: Long?,
    val fixNotes: String,
    val sourceUrl: String,
    val provenance: String,
    val updatedAtMs: Long,
)

/** One [com.kevin.legion.data.local.ChassisQuirk] row, ready for [FleetBackend.upsertChassisQuirk].
 * **A genuine REPLACE-on-conflict upsert, always writing every column** - matching
 * `ChassisQuirk.kt`'s own local `OnConflictStrategy.REPLACE` semantics and the migration's own
 * comment ("REPLACE semantics reference content re-seeded on APK updates"), because unlike
 * [DriveUpload] this table's content CAN legitimately change between calls (a corrected mileage
 * window shipped in a later APK, say) and a plain insert-if-absent would never pick that up.
 * [provenance] is asserted by [FleetReconcile], same posture as [CodeEventUpload]'s own doc
 * comment - always `"DETERMINISTIC"` (parsed from a bundled JSON asset by code). */
data class ChassisQuirkUpload(
    val quirkId: String,
    val chassis: String,
    val engine: String,
    val title: String,
    val symptom: String,
    val verificationSteps: String,
    val mileageLow: Int?,
    val mileageHigh: Int?,
    val severity: String,
    val costLowCents: Long?,
    val costHighCents: Long?,
    val fixNotes: String,
    val sourceUrl: String,
    val provenance: String,
)

/**
 * The Phase 4 fleet seam for `vehicles`, `service_history`, `drives` (wave 1) plus
 * `code_events`, `code_clear_events`, `oil_analyses` and `chassis_quirks` (this wave) -
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`. Mirrors [PlacesBackend]/[EventsBackend]'s shape
 * exactly: narrow, no [io.github.jan.supabase.SupabaseClient] in any signature, every function
 * returns [Result] rather than throwing or returning a nullable. `vehicle_specs`, `build_entries`
 * and `drive_reassignments` are deliberately NOT here - a later wave, per ticket 10's own scope note.
 *
 * **The identity split that makes this interface interesting.** `vehicles` and `service_history`
 * are engine records (`FleetAspectSeeder` defines Vehicle, ServiceHistory, MaintenanceSchedule), so
 * they upload keyed on `origin_guid` from `records.guid`, exactly like [EventsBackend.uploadMigratedEvent]/
 * [PantryBackend.uploadMigratedReceipt]. `drives`, `code_events`, `code_clear_events` and
 * `oil_analyses` are NOT engine records - each already carried its own portable `syncId` before any
 * of this existed (confirmed against `sync/SyncEngine.kt`'s registry and each `@Entity`), so all four
 * upsert by that natural key instead, exactly like [PlacesBackend.upsert]'s `label`. `chassis_quirks`
 * is a THIRD shape again: household-shared reference data keyed on its own natural `quirkId`, with no
 * `vehicleServerId` at all. Three identity shapes in one aspect, because the aspect genuinely has
 * three, not because this interface picked one arbitrarily.
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

    /** Every active (not soft-deleted) code event, server-side. Used to refresh the Room replica
     * (the `code_events` table itself - same "no separate replica table needed" reasoning as
     * [fetchActiveDrives]). Never called from a hot-path read. */
    suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>>

    /** Upserts by [CodeEventUpload.syncId] (`code_events.sync_id`'s own unique constraint) - same
     * "no update, no delete, so a repost is always free" posture as [upsertDrive]. */
    suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent>

    /** Every active (not soft-deleted) code-clear event, server-side. Same replica role as
     * [fetchActiveCodeEvents]. */
    suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>>

    /** Upserts by [CodeClearEventUpload.syncId] - same shape as [upsertCodeEvent]. */
    suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent>

    /** Every active (not soft-deleted) oil analysis, server-side. Same replica role as
     * [fetchActiveCodeEvents]. */
    suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>>

    /** Upserts by [OilAnalysisUpload.syncId] - same shape as [upsertCodeEvent]. */
    suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis>

    /** Every chassis quirk, server-side (`chassis_quirks` has no `deleted_at` column at all - see
     * [RemoteChassisQuirk]'s own doc comment). Used to refresh the Room replica (the
     * `chassis_quirks` table itself, which already plays this dual role locally via
     * `ChassisQuirkDao.upsertAll`'s `OnConflictStrategy.REPLACE`). */
    suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>>

    /** Upserts by [ChassisQuirkUpload.quirkId] - a genuine REPLACE-on-conflict, always overwriting
     * every column, per [ChassisQuirkUpload]'s own doc comment on why this table cannot use
     * insert-if-absent the way [upsertCodeEvent] and its siblings do. */
    suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseFleetBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as [PlacesBackendException]/
 * [EventsBackendException]. */
class FleetBackendException(message: String) : Exception(message)
