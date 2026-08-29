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
 * **CORRECTED 2026-08-29, ticket 26 (`.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`):
 * this doc comment used to say no live upsert-by-serverId would ever exist. [FleetBackend.upsertVehicle]/
 * [VehicleUpload] is exactly that, now that fleet is a real cutover rather than a one-way
 * projection - see [VehicleUpload]'s own doc comment. [uploadMigratedVehicle] stays exactly as it
 * was: the one-time migration replay still needs an insert-if-absent-by-`origin_guid` primitive,
 * and the two coexist the same way [EventsBackend.uploadMigratedEvent]/[EventsBackend.upsert]
 * already do for Notes+Dates.
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
 * One vehicle's identity fields, ready for [FleetBackend.upsertVehicle] - the LIVE write primitive
 * ticket 26 (`.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`) adds alongside
 * [MigratedVehicle]'s one-time-replay insert. **[serverId] is the whole of the identity decision**
 * (that ticket's "RULED 2026-08-29"): `null` means "no server row exists yet for this car" and
 * [SupabaseFleetBackend.upsertVehicle] does a genuine INSERT, returning the uuid Postgres assigns;
 * non-null means "update the row at this uuid", an ordinary `UPDATE ... WHERE id = :serverId`
 * touching only the columns this type carries (never `origin_guid`, which a live edit has no
 * business rewriting - see [RemoteVehicle.originGuid]'s own doc comment for what that column
 * means and why a live write leaves it alone).
 *
 * No `originGuid` field here at all, unlike [MigratedVehicle] - a live-created vehicle has no
 * migration provenance to record, and an existing row's `origin_guid` (if any) is preserved by the
 * UPDATE simply never mentioning that column, exactly as every other partial-PATCH DTO in this
 * package already does (see [VehicleWriteDto]'s own doc comment in [SupabaseFleetBackend]).
 *
 * [odometerBaseline]/[odometerBaselineAtMs] are paired-or-neither, same convention as
 * [RemoteVehicle] and the same server-side constraint (`vehicles_odometer_baseline_paired`) - the
 * caller (`vehicle/FleetEngineStore.kt`) is responsible for null-ing both together when the
 * driver has never actually stated an odometer reading, never sending a fabricated `0`.
 */
data class VehicleUpload(
    val serverId: String?,
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
 * A `public.vehicle_specs` row as Postgres reports it (this wave's migration). **[vehicleServerId]
 * IS the primary key, not a separate row id** - `vehicle_id uuid primary key references
 * public.vehicles (id)`, one row per vehicle, matching [com.kevin.legion.data.local.VehicleSpec]'s
 * own `@PrimaryKey val vehicleId` and its DAO's REPLACE-on-conflict local semantics. No
 * `deleted_at` column (the table's own comment: it is a per-vehicle encyclopedia entry, refreshed in
 * place, not a soft-deletable fact log) - so unlike every `syncId`-keyed sibling in this file there is
 * no `deleted` field here either.
 *
 * [decodedAtMs] is `null` for "never decoded" - [com.kevin.legion.data.local.VehicleSpec.decodedAt]'s
 * `0L` sentinel is translated to a real absence by [FleetReconcile] before this type is ever
 * constructed, same posture as [RemoteChassisQuirk.mileageLow]'s `-1`.
 */
data class RemoteVehicleSpec(
    val vehicleServerId: String,
    val vin: String,
    val engineCylinders: Int?,
    val displacementL: Double?,
    val engineHp: Int?,
    val engineConfig: String,
    val fuelType: String,
    val transmissionStyle: String,
    val transmissionSpeeds: String,
    val driveType: String,
    val bodyClass: String,
    val doors: Int?,
    val series: String,
    val vehicleType: String,
    val manufacturer: String,
    val plantCity: String,
    val plantCountry: String,
    val paintColor: String,
    val paintCode: String,
    val buildNotes: String,
    val decodedAtMs: Long?,
    val provenance: String,
    val updatedAtMs: Long,
)

/** One [com.kevin.legion.data.local.VehicleSpec] row, ready for [FleetBackend.upsertVehicleSpec] -
 * a genuine REPLACE-on-conflict by [vehicleServerId], always writing every column, matching
 * [ChassisQuirkUpload]'s "content can legitimately change between calls" posture (a re-decode
 * refreshing the vPIC fields is exactly that kind of legitimate change). [provenance] is asserted
 * by [FleetReconcile], same "the phone asserts it explicitly" posture as [CodeEventUpload] - always
 * `"DETERMINISTIC"` per the migration file's header: mostly a machine VIN decode, the three manual
 * paint/notes columns notwithstanding. */
data class VehicleSpecUpload(
    val vehicleServerId: String,
    val vin: String,
    val engineCylinders: Int?,
    val displacementL: Double?,
    val engineHp: Int?,
    val engineConfig: String,
    val fuelType: String,
    val transmissionStyle: String,
    val transmissionSpeeds: String,
    val driveType: String,
    val bodyClass: String,
    val doors: Int?,
    val series: String,
    val vehicleType: String,
    val manufacturer: String,
    val plantCity: String,
    val plantCountry: String,
    val paintColor: String,
    val paintCode: String,
    val buildNotes: String,
    val decodedAtMs: Long?,
    val provenance: String,
)

/**
 * A `public.build_entries` row as Postgres reports it (this wave's migration). [costCents] is
 * `Long` cents (CLAUDE.md section 3) - [com.kevin.legion.data.local.BuildEntry.cost] is a `Double`
 * of DOLLARS on the phone (that class's own doc comment records why it was left that way rather
 * than migrated alongside `ServiceRecord.costCents`), so the dollars-to-cents conversion happens at
 * this upload boundary, in [FleetReconcile] for the same testability reason as every other
 * conversion in this wave. `null` means the driver logged what was done with no dollar figure,
 * never `0`, which would assert it was free.
 */
data class RemoteBuildEntry(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val entryType: String,
    val title: String,
    val vendor: String,
    val partNumber: String,
    val costCents: Long?,
    val loggedAtMs: Long,
    val mileage: Int?,
    val notes: String,
    val provenance: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** One [com.kevin.legion.data.local.BuildEntry] row, ready for [FleetBackend.upsertBuildEntry] -
 * same "genuine upsert by natural key, nothing to check for first" shape as [DriveUpload]: the phone
 * never edits a build entry once logged (`BuildEntryDao`'s own doc comment on why `delete` is
 * dormant), so every call here is either a fresh insert or a harmless re-post of identical data.
 * [provenance] is asserted by [FleetReconcile] - always `"USER"`, a driver-authored logbook line, per
 * the migration file's own header. */
data class BuildEntryUpload(
    val syncId: String,
    val vehicleServerId: String,
    val entryType: String,
    val title: String,
    val vendor: String,
    val partNumber: String,
    val costCents: Long?,
    val loggedAtMs: Long,
    val mileage: Int?,
    val notes: String,
    val provenance: String,
)

/**
 * A `public.drive_reassignments` row as Postgres reports it (this wave's migration). A correction
 * RULE over `drives`, not a mutation of the drives themselves - see
 * [com.kevin.legion.data.local.DriveReassignment]'s own class doc for why a rule, not a re-key, is
 * the only safe shape given `drives`' UNION sync semantics. [vehicleServerId] names the car the
 * window is CURRENTLY (mis)attributed to, [newVehicleServerId] the car it should be attributed to
 * instead - ticket 10 calls a lost or mis-parented row here the serious case, not a cosmetic one,
 * because it is the record of a drive having been attributed to the wrong car.
 */
data class RemoteDriveReassignment(
    val serverId: String,
    val syncId: String,
    val vehicleServerId: String,
    val newVehicleServerId: String,
    val fromAtMs: Long,
    val toAtMs: Long,
    val provenance: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** One [com.kevin.legion.data.local.DriveReassignment] row, ready for
 * [FleetBackend.upsertDriveReassignment] - same upsert-by-syncId shape as [BuildEntryUpload].
 * [provenance] is asserted by [FleetReconcile] - always `"USER"`, a correction a person made in the
 * car manager UI, per the migration file's own header. */
data class DriveReassignmentUpload(
    val syncId: String,
    val vehicleServerId: String,
    val newVehicleServerId: String,
    val fromAtMs: Long,
    val toAtMs: Long,
    val provenance: String,
)

/**
 * The Phase 4 fleet seam for `vehicles`, `service_history`, `drives` (wave 1), `code_events`,
 * `code_clear_events`, `oil_analyses`, `chassis_quirks` (wave 3), and `vehicle_specs`,
 * `build_entries`, `drive_reassignments` (this wave, the last three tables) -
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`. Mirrors [PlacesBackend]/[EventsBackend]'s shape
 * exactly: narrow, no [io.github.jan.supabase.SupabaseClient] in any signature, every function
 * returns [Result] rather than throwing or returning a nullable. Every fleet table now has a server
 * home.
 *
 * **The identity split that makes this interface interesting.** `vehicles` and `service_history`
 * are engine records (`FleetAspectSeeder` defines Vehicle, ServiceHistory, MaintenanceSchedule), so
 * they upload keyed on `origin_guid` from `records.guid`, exactly like [EventsBackend.uploadMigratedEvent]/
 * [PantryBackend.uploadMigratedReceipt]. `drives`, `code_events`, `code_clear_events`,
 * `oil_analyses`, `build_entries` and `drive_reassignments` are NOT engine records - each already
 * carried its own portable `syncId` before any of this existed (confirmed against
 * `sync/SyncEngine.kt`'s registry and each `@Entity`), so all six upsert by that natural key instead,
 * exactly like [PlacesBackend.upsert]'s `label`. `chassis_quirks` is a THIRD shape: household-shared
 * reference data keyed on its own natural `quirkId`, with no `vehicleServerId` at all. `vehicle_specs`
 * is a FOURTH shape again - keyed on its own natural `vehicleId` (`SyncEngine.kt`'s `naturalPk = true`
 * entry), one row per vehicle, `vehicle_id` doubling as both primary key and the foreign key to
 * `vehicles.id` - so unlike `chassis_quirks` it DOES carry a `vehicleServerId`, but unlike the six
 * `syncId`-keyed tables that id IS the whole identity, not a parallel key alongside a separate row id.
 * Four identity shapes in one aspect, because the aspect genuinely has four, not because this
 * interface picked one arbitrarily.
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

    /** The live cutover write (ticket 26): insert when [VehicleUpload.serverId] is null, update
     * the named row otherwise. See [VehicleUpload]'s own doc comment for the full identity
     * contract - this is deliberately NOT keyed on `origin_guid`, which stays a migration-only
     * concept. Returns the row Postgres now holds, so a first insert can record the uuid it was
     * just assigned. */
    suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle>

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

    /** Every vehicle_spec row, server-side (no `deleted_at` filter - see [RemoteVehicleSpec]'s own
     * doc comment for why this table has no such column). Used to refresh the Room replica (the
     * `vehicle_specs` table itself, which already plays this dual role locally via
     * `VehicleSpecDao.upsertStamped`'s `OnConflictStrategy.REPLACE`). */
    suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>>

    /** Upserts by [VehicleSpecUpload.vehicleServerId] (`vehicle_specs.vehicle_id`'s own PK/FK) - a
     * genuine REPLACE-on-conflict, always overwriting every column, same posture as
     * [upsertChassisQuirk]. */
    suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec>

    /** Every active (not soft-deleted) build-sheet entry, server-side. Used to refresh the Room
     * replica (the `build_entries` table itself - same "no separate replica table needed" reasoning
     * as [fetchActiveDrives]). Never called from a hot-path read. */
    suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>>

    /** Upserts by [BuildEntryUpload.syncId] (`build_entries.sync_id`'s own unique constraint) - same
     * "no update, no delete, so a repost is always free" posture as [upsertDrive]. */
    suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry>

    /** Every active (not soft-deleted) drive-reassignment correction, server-side. Same replica role
     * as [fetchActiveBuildEntries]. */
    suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>>

    /** Upserts by [DriveReassignmentUpload.syncId] - same shape as [upsertBuildEntry]. A client
     * retry of an already-applied correction is a legitimate no-op re-post, per the migration file's
     * own comment on why this table carries no CHECK against naming the same vehicle twice. */
    suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseFleetBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as [PlacesBackendException]/
 * [EventsBackendException]. */
class FleetBackendException(message: String) : Exception(message)
